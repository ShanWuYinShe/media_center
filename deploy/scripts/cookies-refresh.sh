#!/usr/bin/env bash
# 刷新 YouTube cookies 会话：用**现有 cookies** 以与 vod_extractor 完全相同的
# extractor-args 完成一次真实解析，yt-dlp 会把 YouTube 回发的新会话 token 写回
# cookie 文件；成功后原子替换原文件（保留原属主/权限）。无需浏览器重新导出——
# 只有当 cookies 彻底失效（刷新解析本身报 bot_check）时才需要人工导出。
#
# 用法: scripts/cookies-refresh.sh [视频URL]
#   默认 URL 为已知的短测试视频；需在 deploy/ 目录的宿主机上执行（make cookies-refresh）。
set -euo pipefail
cd "$(dirname "$0")/.."
# .env 的值可能含分号（extractor-args 的两个键值对），直接 source 会被 bash 当成
# 命令分隔符执行；只按行提取所需变量原文，不做 shell 求值。
VOD_EXTRACTOR_EXTRACTOR_ARGS="$(grep -E '^VOD_EXTRACTOR_EXTRACTOR_ARGS=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"

URL="${1:-https://www.youtube.com/watch?v=jNQXAC9IVRw}"
COOKIE="data/vod/youtube-cookies.txt"
TMP="data/vod/cookies-refresh.tmp"

[ -f "$COOKIE" ] || { echo "cookies 不存在: $COOKIE"; exit 1; }
OWNER="$(stat -c %u:%g "$COOKIE")"
MODE="$(stat -c %a "$COOKIE")"
CID="$(docker compose --env-file versions.env ps -q vod_extractor)"
[ -n "$CID" ] || { echo "vod_extractor 容器未运行"; exit 1; }
NET="$(docker inspect "$CID" --format '{{range $k,$_ := .NetworkSettings.Networks}}{{$k}}{{end}}')"
IMG="$(docker inspect "$CID" --format {{.Config.Image}})"
cp "$COOKIE" "$TMP"
chmod 600 "$TMP"

ARGS=()
[ -n "${VOD_EXTRACTOR_EXTRACTOR_ARGS:-}" ] && ARGS=(--extractor-args "$VOD_EXTRACTOR_EXTRACTOR_ARGS")

echo "刷新中: $URL"
docker run --rm --user 0:0 --network "$NET" -v "$(pwd)/data/vod":/work -w /work \
  "$IMG" yt-dlp --cookies /work/cookies-refresh.tmp --no-warnings \
  "${ARGS[@]}" --dump-single-json --no-playlist "$URL" \
  > /dev/null 2>/tmp/cookies-refresh.err \
  || { echo "刷新失败( cookies 可能已彻底失效，需浏览器重新导出):"; tail -5 /tmp/cookies-refresh.err; exit 1; }

SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
$SUDO install -o "${OWNER%%:*}" -g "${OWNER##*:}" -m "$MODE" "$TMP" "$COOKIE"
rm -f "$TMP" /tmp/cookies-refresh.err
echo "cookies 已刷新并回写: $COOKIE (属主/权限保持 $OWNER $MODE)；备份保留在 data/vod/cookies.bak.*.txt"
echo "验证: docker exec $$(docker compose --env-file versions.env ps -q vod_extractor) wget -qO- http://127.0.0.1:8790/health | grep cookies"