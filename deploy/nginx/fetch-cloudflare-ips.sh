#!/bin/sh
# Fetch Cloudflare IP ranges and generate nginx real-ip include.
# 与 workwork services/nginx 同源（同一维护者）；本站无 cron，容器每次
# 启动时经 /docker-entrypoint.d/20-fetch-cloudflare-ips.sh 刷新一次。
set -eu

OUT=/etc/nginx/cloudflare-ips.conf
TMP=$(mktemp /tmp/cf-ips.XXXXXX)
V4=$(mktemp /tmp/cf-v4.XXXXXX)
V6=$(mktemp /tmp/cf-v6.XXXXXX)
trap 'rm -f "$TMP" "$V4" "$V6"' EXIT

write_include() {
  generated_at=$1
  {
    echo "# Cloudflare IP ranges — auto-generated at ${generated_at}"
    echo "# Cloudflare IPv4"
    while IFS= read -r ip; do
      [ -n "$ip" ] && echo "set_real_ip_from $ip;"
    done < "$V4"
    echo "# Cloudflare IPv6"
    while IFS= read -r ip; do
      [ -n "$ip" ] && echo "set_real_ip_from $ip;"
    done < "$V6"
    echo "real_ip_header CF-Connecting-IP;"
  } > "$TMP"

  if ! grep -q '^set_real_ip_from ' "$TMP"; then
    echo "generated Cloudflare include has no IP ranges" >&2
    return 1
  fi
  mv "$TMP" "$OUT"
}

write_fallback_ranges() {
  cat > "$V4" <<'EOF'
173.245.48.0/20
103.21.244.0/22
103.22.200.0/22
103.31.4.0/22
141.101.64.0/18
108.162.192.0/18
190.93.240.0/20
188.114.96.0/20
197.234.240.0/22
198.41.128.0/17
162.158.0.0/15
104.16.0.0/13
104.24.0.0/14
172.64.0.0/13
131.0.72.0/22
EOF
  cat > "$V6" <<'EOF'
2400:cb00::/32
2606:4700::/32
2803:f800::/32
2405:b500::/32
2405:8100::/32
2a06:98c0::/29
2c0f:f248::/32
EOF
}

if curl -fsSL --connect-timeout 10 https://www.cloudflare.com/ips-v4 -o "$V4" &&
   curl -fsSL --connect-timeout 10 https://www.cloudflare.com/ips-v6 -o "$V6" &&
   [ -s "$V4" ] && [ -s "$V6" ]; then
  write_include "$(date -Iseconds)"
  exit 0
fi

if [ -s "$OUT" ] && grep -q '^set_real_ip_from ' "$OUT"; then
  echo "failed to fetch Cloudflare IP ranges; keeping existing include" >&2
  exit 1
fi

echo "failed to fetch Cloudflare IP ranges; writing built-in fallback ranges" >&2
write_fallback_ranges
write_include "fallback"
