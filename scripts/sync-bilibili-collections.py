#!/usr/bin/env python3
"""从 B 站 view API 的 ugc_season 采集合集关系，写入 media_server catalog。

背景:
- media_server 的 catalog 是内容与审核的唯一事实源；合集只存"分组关系"
  （collection_id / title / 有序 bvid），不新增视频、不改审核状态。
- 公开播放队列只返回合集里已 approved 的剧集，因此采集脚本可以安全地
  把来源合集全量写进来，未过审剧集不会绕过审核关卡。
- 服务器 IP 访问 B 站会被风控，本脚本应在可访问 B 站的开发机运行；通过
  SSH 隧道把内部接口映射到本机（或直接在服务器上跑 catalog 拉取部分）。

用法:
  # 1) 只看统计，不写库
  scripts/sync-bilibili-collections.py \
      --catalog-url http://127.0.0.1:18787/api/v1 --dry-run

  # 2) 生成快照文件
  scripts/sync-bilibili-collections.py \
      --catalog-url http://127.0.0.1:18787/api/v1 --output /tmp/collections.json

  # 3) 直接写入（内部令牌从环境变量读取，避免进 shell history）
  MEDIA_SERVER_INTERNAL_TOKEN=... scripts/sync-bilibili-collections.py \
      --catalog-url http://127.0.0.1:18787/api/v1 \
      --internal-url http://127.0.0.1:18787 --push

  # 4) 只处理前 N 个目录视频（调试）
  ... --limit 50

SSH 隧道示例（把服务器容器端口映射到本机）:
  ssh -N -L 18787:<容器IP>:8787 workwork-server
"""

import argparse
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120 Safari/537.36"
)
BILI_VIEW = "https://api.bilibili.com/x/web-interface/view"
PAGE_SIZE = 40


def log(msg):
    print(f"[sync-bilibili-collections] {msg}", flush=True)


def http_json(url, timeout, headers=None, data=None, method=None):
    body = None
    if data is not None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method=method or ("POST" if body else "GET"),
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
            "Content-Type": "application/json",
            **(headers or {}),
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
    return json.loads(raw.decode("utf-8"))


def normalize_bvid(value):
    value = (value or "").strip()
    if not value:
        return ""
    # catalog 的分P条目形态为 bvid_pN；合集关系按 base bvid 存。
    idx = value.rfind("_p")
    if idx > 0 and value[idx + 2:].isdigit():
        return value[:idx]
    return value


def fetch_catalog(base_url, provider, timeout):
    """拉取 catalog 中全部 approved 视频（按 provider），返回有序 dict[id]=title。"""
    base = base_url.rstrip("/")
    cats = http_json(
        f"{base}/providers/{urllib.parse.quote(provider)}/categories", timeout
    ).get("items") or []
    items = {}
    for cat in cats:
        cat_id = cat.get("id")
        if not cat_id:
            continue
        page = 1
        while page <= 200:
            url = (
                f"{base}/providers/{urllib.parse.quote(provider)}/categories/"
                f"{urllib.parse.quote(str(cat_id))}/videos?page={page}&pageSize={PAGE_SIZE}"
            )
            payload = http_json(url, timeout)
            page_items = payload.get("items") or []
            for item in page_items:
                bvid = normalize_bvid(item.get("id"))
                if bvid:
                    items.setdefault(bvid, item.get("title") or "")
            total = int(payload.get("total") or 0)
            if len(page_items) < PAGE_SIZE or (total > 0 and page * PAGE_SIZE >= total):
                break
            page += 1
        log(f"catalog {cat_id}: 累计 {len(items)} 条")
    return items


def fetch_bilibili_view(bvid, timeout, max_retries, delay):
    """取单视频 view JSON；风控(-352/-412/-403)退避重试，-404 视为不存在。"""
    url = f"{BILI_VIEW}?bvid={urllib.parse.quote(bvid)}"
    for attempt in range(max_retries + 1):
        try:
            data = http_json(
                url,
                timeout,
                headers={"Referer": "https://www.bilibili.com/"},
            )
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            if attempt >= max_retries:
                log(f"{bvid}: 请求失败，跳过（{exc}）")
                return None
            wait = delay + 5 * (attempt + 1)
            log(f"{bvid}: 请求失败，{wait:.1f}s 后重试（{exc}）")
            time.sleep(wait)
            continue
        code = data.get("code")
        if code == 0:
            return data.get("data") or {}
        if code in (-352, -412, -403):
            wait = max(10, delay) * (2 ** attempt) + random.uniform(0, 2)
            if attempt >= max_retries:
                log(f"{bvid}: 连续风控 code={code}，跳过")
                return None
            log(f"{bvid}: B 站风控 code={code}，{wait:.1f}s 后重试")
            time.sleep(wait)
            continue
        return None
    return None


def extract_season(data):
    """从 view data 提取 ugc_season；保持 episodes 原始顺序（合集列表顺序）。"""
    season = data.get("ugc_season")
    if not isinstance(season, dict):
        return None
    season_id = season.get("id")
    if season_id is None:
        return None
    seen = set()
    episodes = []
    for section in season.get("sections") or []:
        if not isinstance(section, dict):
            continue
        for episode in section.get("episodes") or []:
            if not isinstance(episode, dict):
                continue
            bvid = normalize_bvid(episode.get("bvid"))
            if bvid and bvid not in seen:
                seen.add(bvid)
                episodes.append(bvid)
    if not episodes:
        return None
    return {
        "id": str(season_id),
        "title": (season.get("title") or "").strip(),
        "videoIds": episodes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--catalog-url", default="", help="media_server 公开基址，如 http://127.0.0.1:18787/api/v1")
    parser.add_argument("--catalog-file", default="", help="离线 catalog 快照（JSON: [{id,title}]），与 --catalog-url 二选一")
    parser.add_argument("--provider", default="bilibili")
    parser.add_argument("--internal-url", default="", help="内部接口基址，如 http://127.0.0.1:18787")
    parser.add_argument("--token", default=os.environ.get("MEDIA_SERVER_INTERNAL_TOKEN", ""))
    parser.add_argument("--push", action="store_true", help="POST 到 /internal/catalog/collections")
    parser.add_argument("--output", default="", help="把快照 JSON 写到文件")
    parser.add_argument("--dry-run", action="store_true", help="只采集并打印统计，不写文件/不推送")
    parser.add_argument("--limit", type=int, default=0, help="最多请求多少个 B 站视频（0=不限制，调试用）")
    parser.add_argument("--delay", type=float, default=0.5, help="B 站请求间隔基数（秒）")
    parser.add_argument("--timeout", type=float, default=20.0, help="HTTP 超时（秒）")
    parser.add_argument("--max-retries", type=int, default=3)
    args = parser.parse_args()

    if not args.catalog_url and not args.catalog_file:
        parser.error("需要 --catalog-url 或 --catalog-file")
    if args.push and not args.internal_url:
        parser.error("--push 需要 --internal-url")
    if args.push and not args.token:
        parser.error("--push 需要 --token 或 MEDIA_SERVER_INTERNAL_TOKEN")

    if args.catalog_file:
        raw = json.load(open(args.catalog_file, encoding="utf-8"))
        if isinstance(raw, dict):
            raw = raw.get("items") or []
        catalog = {}
        for item in raw:
            bvid = normalize_bvid(str(item.get("id") or ""))
            if bvid:
                catalog.setdefault(bvid, item.get("title") or "")
    else:
        catalog = fetch_catalog(args.catalog_url, args.provider, args.timeout)
    log(f"catalog approved 视频: {len(catalog)} 条（provider={args.provider}）")

    known = set()
    seasons = {}
    requests = 0
    for bvid in catalog:
        if args.limit and requests >= args.limit:
            break
        if bvid in known:
            continue
        requests += 1
        data = fetch_bilibili_view(bvid, args.timeout, args.max_retries, args.delay)
        if data:
            season = extract_season(data)
            if season:
                if season["id"] not in seasons:
                    seasons[season["id"]] = season
                    log(f"合集 {season['id']}《{season['title']}》{len(season['videoIds'])} 集（由 {bvid} 发现）")
                known.update(season["videoIds"])
        if requests % 20 == 0:
            log(f"进度: 请求 {requests}，发现合集 {len(seasons)}")
        time.sleep(max(0.0, args.delay + random.uniform(0, 0.2)))

    catalog_ids = set(catalog)
    collections = [
        s for s in seasons.values()
        if catalog_ids.intersection(s["videoIds"])
    ]
    total_members = sum(len(s["videoIds"]) for s in collections)
    covered = set()
    for s in collections:
        covered.update(catalog_ids.intersection(s["videoIds"]))
    log(
        f"完成: B 站请求 {requests} 次，合集 {len(collections)} 个，"
        f"剧集 {total_members} 条，其中已审核 {len(covered)} 条"
    )
    payload = {"provider": args.provider, "collections": collections}

    if args.output and not args.dry_run:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
            f.write("\n")
        log(f"快照已写入 {args.output}")

    if args.push and not args.dry_run:
        url = args.internal_url.rstrip("/") + "/internal/catalog/collections"
        try:
            result = http_json(
                url,
                args.timeout,
                headers={"X-Internal-Token": args.token},
                data=payload,
                method="POST",
            )
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:500]
            log(f"写入失败: HTTP {exc.code} {body}")
            return 1
        log(f"写入成功: {json.dumps(result, ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
