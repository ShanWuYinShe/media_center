#!/bin/sh
# 官方 nginx 镜像 entrypoint 钩子：每次容器启动刷新 Cloudflare IP 段。
# 失败不阻断启动（fetch 脚本会写内置兜底段或保留旧 include）。
set -e

/usr/local/bin/fetch-cloudflare-ips.sh || echo "WARNING: fetch cloudflare ips failed, using cached include" >&2
