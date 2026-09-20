#!/usr/bin/env bash
# media_server 冒烟检查：对部署实例的核心只读接口做 curl + JSON 断言。
#
# 用法: scripts/smoke.sh <base-url>
#   例: scripts/smoke.sh https://yinghuoren.com/api/media
#       scripts/smoke.sh http://localhost:8787
#
# 覆盖端点（见 media_server/API.md）:
#   GET /health                                          → 200 {"status":"ok"}
#   GET /api/health                                      → 200 {"status":"ok"}
#   GET /api/providers                                   → 200 items 为数组
#   GET /api/providers/<id>/categories                   → 200 items 为数组
#   GET /api/providers/<id>/categories/<cid>/videos      → 200 items 为数组
#
# <id>/<cid> 从前一响应动态取首项；catalog 为空时对应条目标 SKIP（不算失败）。
# 任一 FAIL 时退出码 1，全部通过退出码 0。
set -uo pipefail

BASE="${1:-}"
if [[ -z "${BASE}" ]] || [[ "${BASE}" == "-h" ]] || [[ "${BASE}" == "--help" ]]; then
  echo "用法: scripts/smoke.sh <base-url>（如 https://yinghuoren.com/api/media）" >&2
  [[ -z "${BASE}" ]] && exit 2
  exit 0
fi
BASE="${BASE%/}"

command -v curl >/dev/null || { echo "需要 curl" >&2; exit 2; }
command -v python3 >/dev/null || { echo "需要 python3" >&2; exit 2; }

FAILURES=0

# loopback 目标不走代理（http_proxy 环境存在时误测本地实例会拿到代理 503）；
# 远程目标仍尊重 http_proxy/https_proxy 环境变量。
CURL=(curl -sS --max-time 15 --noproxy 'localhost,127.0.0.1,::1')

# check <名称> <url> <python 断言表达式，变量 data 为解析后的 JSON>
check() {
  local name="$1" url="$2" assertion="$3" http_code body curl_err
  http_code="$("${CURL[@]}" -o /tmp/smoke-body.$$ -w '%{http_code}' "$url" 2>/tmp/smoke-err.$$)"
  curl_rc=$?
  curl_err="$(cat /tmp/smoke-err.$$)"
  body="$(cat /tmp/smoke-body.$$ 2>/dev/null)"
  rm -f /tmp/smoke-body.$$ /tmp/smoke-err.$$

  if [[ ${curl_rc} -ne 0 ]]; then
    echo "FAIL ${name}（curl 失败: ${curl_err:-网络错误}）"
    FAILURES=$((FAILURES + 1))
    return 1
  fi
  if [[ "${http_code}" != "200" ]]; then
    echo "FAIL ${name}（HTTP ${http_code}）"
    FAILURES=$((FAILURES + 1))
    return 1
  fi
  if ! printf '%s' "${body}" | python3 -c "
import json, sys
body = sys.stdin.read()
try:
    data = json.loads(body)
except json.JSONDecodeError as e:
    sys.exit(f'响应不是合法 JSON: {e}')
if not (${assertion}):
    sys.exit('JSON 断言未通过')
" 2>/tmp/smoke-assert.$$; then
    echo "FAIL ${name}（$(cat /tmp/smoke-assert.$$)）"
    rm -f /tmp/smoke-assert.$$
    FAILURES=$((FAILURES + 1))
    return 1
  fi
  rm -f /tmp/smoke-assert.$$
  echo "PASS ${name}"
}

# first_of <json> <python 表达式:从 data 取首项字段> → 输出值或空（非法输入返回空）
first_of() {
  printf '%s' "$1" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
items = data.get('items') or []
print(items[0]['$2'] if items else '')
" 2>/dev/null
}

echo "== media_server 冒烟: ${BASE} =="

check "/health"            "${BASE}/health"              'data.get("status") == "ok"'
check "/api/health"        "${BASE}/api/health"          'data.get("status") == "ok"'
check "/api/providers"     "${BASE}/api/providers"       'isinstance(data.get("items"), list)'

PROVIDERS_JSON="$("${CURL[@]}" "${BASE}/api/providers" 2>/dev/null)" || PROVIDERS_JSON=""
PROVIDER_ID="$(first_of "${PROVIDERS_JSON:-{\}}" id || true)"
if [[ -z "${PROVIDER_ID}" ]]; then
  echo "SKIP /api/providers/<id>/categories（catalog 无 provider）"
  if [[ ${FAILURES} -gt 0 ]]; then
    echo "== 结果: FAIL（${FAILURES} 项失败）=="
    exit 1
  fi
  echo "== 结果: 通过（含 SKIP）=="
  exit 0
fi

if check "/api/providers/${PROVIDER_ID}/categories" \
      "${BASE}/api/providers/${PROVIDER_ID}/categories" \
      'isinstance(data.get("items"), list)'; then
  CATEGORIES_JSON="$("${CURL[@]}" "${BASE}/api/providers/${PROVIDER_ID}/categories" 2>/dev/null)" || CATEGORIES_JSON=""
  CATEGORY_ID="$(first_of "${CATEGORIES_JSON:-{\}}" id || true)"
  if [[ -z "${CATEGORY_ID}" ]]; then
    echo "SKIP /api/providers/${PROVIDER_ID}/categories/<cid>/videos（该 provider 无分类）"
  else
    check "/api/providers/${PROVIDER_ID}/categories/${CATEGORY_ID}/videos" \
          "${BASE}/api/providers/${PROVIDER_ID}/categories/${CATEGORY_ID}/videos?page=1&pageSize=1" \
          'isinstance(data.get("items"), list)'
  fi
fi

if [[ ${FAILURES} -gt 0 ]]; then
  echo "== 结果: FAIL（${FAILURES} 项失败）=="
  exit 1
fi
echo "== 结果: 全部 PASS =="
