#!/usr/bin/env python3
"""巡检 media_server catalog 中 B站条目的可播放性,输出失效清单(默认只报告不下架)。

背景:
- catalog 的审核关口只保证"上架时"可播放(CATEGORY_CURATION_GUIDE.md「可播放性」);
  B站视频上架后被 UP 主删除/设为不可见时,App 点开就会解析失败(view -404/62002),
  用户侧表现为"有的视频加载失败"。本脚本用于定期巡检并清理这类脏数据。
- 服务器 IP 访问 B站会被风控,脚本应在可访问 B站的开发机运行;下架走
  /internal/catalog/review(软 reject,可恢复,不硬删数据),需要 internal token 与
  到 media_server 的可达路径(开发机直连公网或 SSH 隧道)。

用法(在仓库根目录):
    python3 scripts/check-bilibili-catalog.py                    # 全分类巡检,只打报告
    python3 scripts/check-bilibili-catalog.py --category movies  # 只巡检一个分类
    python3 scripts/check-bilibili-catalog.py --limit 50         # 每分类随机抽样 N 条
    MEDIA_SERVER_INTERNAL_TOKEN=... python3 scripts/check-bilibili-catalog.py --apply
        # --apply: 把失效条目按分类 reject 下架(软下架,可人工重新 approve)

输出: 每分类统计 + 失效条目(bvid/标题/原因);--output 可落 JSON 报告。
"""
import argparse
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

DEFAULT_BASE = "https://media.chaoset.com/api/v1"
BILI_VIEW = "https://api.bilibili.com/x/web-interface/view?bvid="
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0 Safari/537.36")
# 风控类业务码/HTTP 码:命中时暂停,不把它当成"条目失效"(避免误下架正常视频)。
RISK_CODES = {-352, -412, -403, 429, "HTTP412", "HTTP429", "HTTP403"}
# 内容类失效码:-404 视频不存在;62002/62012 稿件不可见/审核中(与 App 端文案映射一致)。
GONE_CODES = {-404}
INVISIBLE_CODES = {62002, 62012}


def http_json(url, headers=None, timeout=20):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def http_post_json(url, payload, headers, timeout=30):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={**headers, "Content-Type": "application/json"},
                                 method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def fetch_catalog_categories(base):
    return http_json(f"{base}/providers/bilibili/categories")["items"]


def fetch_category_videos(base, category_id, limit=0, seed=0):
    """拉某分类已审核视频(分页 pageSize=20,服务端上限);limit>0 时随机抽样。"""
    videos, page = [], 1
    while True:
        d = http_json(f"{base}/providers/bilibili/categories/{category_id}/videos?page={page}&pageSize=20")
        items = d.get("items") or []
        if not items:
            break
        videos.extend(items)
        total = d.get("total") or 0
        if len(videos) >= total:
            break
        page += 1
    if limit and len(videos) > limit:
        rng = random.Random(seed or int(time.time()))
        videos = rng.sample(videos, limit)
    return videos


def check_bvideo(bvid):
    """调 B站 view 接口判定条目当前是否可播放;返回 (状态, code, 说明)。"""
    try:
        d = http_json(BILI_VIEW + bvid, headers={"User-Agent": UA, "Referer": "https://www.bilibili.com/"})
    except urllib.error.HTTPError as e:
        code = f"HTTP{e.code}"
        return ("risk" if code in RISK_CODES else "error"), code, str(e.reason)[:80]
    except Exception as e:  # 网络异常不算条目失效
        return "error", "EXC", str(e)[:80]
    code = d.get("code")
    msg = d.get("message") or ""
    if code == 0 and (d.get("data") or {}).get("cid"):
        return "ok", code, ""
    if code in GONE_CODES:
        return "gone", code, msg
    if code in INVISIBLE_CODES:
        return "invisible", code, msg
    if code in RISK_CODES:
        return "risk", code, msg
    return "error", code, msg


def internal_api_url(base, path):
    """/internal/* 不在 /api/v1 前缀下,从 base 只取 scheme+host 拼。"""
    parts = urlsplit(base.rstrip("/"))
    return f"{parts.scheme}://{parts.netloc}{path}"


# 注意:/internal/* 不对公网暴露(nginx 直接 404,见 deploy/nginx/default.conf.template),
# --apply 只能在能直达 media_server 的地方执行——服务器上经 docker compose exec
# media_server wget 调,或本机 ssh -L 隧道到容器 8787。


def apply_reject(base, internal_token, category_id, bvid, reason):
    """把失效条目软下架(review status=rejected),可人工恢复。"""
    return http_post_json(
        internal_api_url(base, "/internal/catalog/review"),
        {"categoryId": category_id, "provider": "bilibili", "nativeId": bvid,
         "status": "rejected", "reviewer": "check-bilibili-catalog"},
        {"X-Internal-Token": internal_token},
    )


def main():
    ap = argparse.ArgumentParser(description="巡检 catalog 中 B站视频的可播放性")
    ap.add_argument("--base", default=os.environ.get("MEDIA_SERVER_URL", DEFAULT_BASE),
                    help="服务端 API 根地址(默认 %(default)s)")
    ap.add_argument("--category", action="append", default=[], help="只巡检指定分类,可重复")
    ap.add_argument("--limit", type=int, default=0, help="每分类随机抽样条数(0=全量)")
    ap.add_argument("--seed", type=int, default=0, help="抽样随机种子(便于复现)")
    ap.add_argument("--interval", type=float, default=0.8, help="B站请求间隔秒(默认 0.8)")
    ap.add_argument("--apply", action="store_true",
                    help="对失效条目执行软下架(需要 internal token;internal 不对公网暴露,"
                         "需在服务器侧/SSH 隧道内执行)")
    ap.add_argument("--internal-base", default="",
                    help="internal 接口主机基址(默认从 --base 推导;隧道场景可指 http://127.0.0.1:8787)")
    ap.add_argument("--internal-token", default=os.environ.get("MEDIA_SERVER_INTERNAL_TOKEN", ""),
                    help="内部接口令牌(默认读 MEDIA_SERVER_INTERNAL_TOKEN)")
    ap.add_argument("--output", default="", help="JSON 报告输出路径")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    cats = fetch_catalog_categories(base)
    if args.category:
        cats = [c for c in cats if c["id"] in set(args.category)]
    if not cats:
        print("没有匹配的分类", file=sys.stderr)
        return 2
    if args.apply and not args.internal_token:
        print("--apply 需要 internal token(环境变量 MEDIA_SERVER_INTERNAL_TOKEN 或 --internal-token)", file=sys.stderr)
        return 2

    report = {"base": base, "checkedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
              "categories": {}, "gone": [], "invisible": [], "errors": [], "risks": 0}
    risk_streak = 0
    for cat in cats:
        cid = cat["id"]
        print(f"== {cid} ({cat.get('title', '')}) ==", flush=True)
        videos = fetch_category_videos(base, cid, args.limit, args.seed)
        counts = {"total": len(videos), "ok": 0, "gone": 0, "invisible": 0, "error": 0}
        for i, v in enumerate(videos):
            native_id = v.get("id") or ""
            bvid = re.sub(r"_p\d+$", "", native_id)  # 分P条目按主 bvid 校验
            state, code, msg = check_bvideo(bvid)
            item = {"categoryId": cid, "videoId": native_id, "bvid": bvid,
                    "title": v.get("title"), "code": code, "detail": msg}
            if state == "ok":
                counts["ok"] += 1
                risk_streak = 0
            elif state == "gone":
                counts["gone"] += 1
                report["gone"].append(item)
            elif state == "invisible":
                counts["invisible"] += 1
                report["invisible"].append(item)
            else:
                counts["error"] += 1
                report["errors"].append(item)
                if state == "risk":
                    risk_streak += 1
                    report["risks"] += 1
                    print(f"  风控码 {code}({bvid}),暂停 30s", flush=True)
                    time.sleep(30)
                    if risk_streak >= 3:
                        print("连续风控,中止巡检(报告已记录当前进度)", file=sys.stderr)
                        break
            if i % 50 == 0 and i:
                print(f"  {i}/{len(videos)} ok={counts['ok']} gone={counts['gone']} "
                      f"invisible={counts['invisible']}", flush=True)
            time.sleep(args.interval)
        report["categories"][cid] = counts
        print(f"  => total={counts['total']} ok={counts['ok']} gone={counts['gone']} "
              f"invisible={counts['invisible']} error={counts['error']}", flush=True)
        if risk_streak >= 3:
            break

    bad = report["gone"] + report["invisible"]
    print("\n== 失效条目 ==")
    for it in bad:
        print(f"  {it['categoryId']:14s} {it['bvid']:14s} code={it['code']} {it['title']}")
    print(f"\n合计: gone={len(report['gone'])} invisible={len(report['invisible'])} "
          f"error={len(report['errors'])}")

    if args.output:
        with open(args.output, "w") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"报告: {args.output}")

    if args.apply and bad:
        print("\n== 软下架(reject) ==")
        failed = 0
        apply_base = args.internal_base or base
        for it in bad:
            try:
                apply_reject(apply_base, args.internal_token, it["categoryId"], it["videoId"], it["detail"])
                print(f"  rejected {it['categoryId']} {it['videoId']}")
            except Exception as e:
                failed += 1
                print(f"  FAILED {it['categoryId']} {it['videoId']}: {e}", file=sys.stderr)
            time.sleep(0.1)
        print(f"下架完成: {len(bad) - failed}/{len(bad)}")
    elif bad:
        print("\n未下架(--apply 才会写库);确认后执行: "
              "MEDIA_SERVER_INTERNAL_TOKEN=... python3 scripts/check-bilibili-catalog.py --apply")
    return 0


if __name__ == "__main__":
    sys.exit(main())
