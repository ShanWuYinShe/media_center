#!/usr/bin/env bash
# 一次性配置 GitHub Actions 发版 secrets（.github/workflows/release-app.yml 使用）。
# 不打印任何密钥内容；默认只打印计划，加 --apply 才真正写入 GitHub Secrets。
set -euo pipefail

REPO=""
KEYSTORE_PATH="${ANDROID_KEYSTORE_PATH:-${HOME}/.android/debug.keystore}"
KEY_ALIAS="${ANDROID_KEYSTORE_KEY_ALIAS:-androiddebugkey}"
APPLY=0
DEBUG_DEFAULTS=0
SKIP_TOKEN=0

usage() {
  cat <<'EOF'
用法: scripts/setup-gh-release-secrets.sh [选项]

为 .github/workflows/release-app.yml 配置 GitHub Actions secrets。
默认只打印计划（不读取、不写入任何密钥）；加 --apply 才写入，需要 gh 已登录且对仓库有 admin 权限。

选项:
  --keystore PATH   签名 keystore 路径（默认: ~/.android/debug.keystore）
  --alias ALIAS     key alias（默认: androiddebugkey）
  --debug-defaults  使用 Android 标准 debug keystore 默认口令，免交互输入
                    （仅当 keystore 确为默认 debug keystore 时使用）
  --skip-token      不设置 APP_API_TOKEN（例如已在 GitHub 网页配置过）
  --repo OWNER/REPO 目标仓库（默认自动从 gh repo view 读取）
  --apply           真正写入 GitHub Secrets
  -h, --help        显示本帮助

也可用环境变量提供值（不会打印）:
  ANDROID_KEYSTORE_PATH、ANDROID_KEYSTORE_KEY_ALIAS、
  ANDROID_KEYSTORE_STORE_PASSWORD、ANDROID_KEYSTORE_KEY_PASSWORD、APP_API_TOKEN
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --keystore) KEYSTORE_PATH="${2:?--keystore 需要路径}"; shift 2 ;;
    --alias) KEY_ALIAS="${2:?--alias 需要值}"; shift 2 ;;
    --debug-defaults) DEBUG_DEFAULTS=1; shift ;;
    --skip-token) SKIP_TOKEN=1; shift ;;
    --repo) REPO="${2:?--repo 需要 OWNER/REPO}"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ -n "${REPO}" ]; then
  TARGET_REPO="${REPO}"
else
  TARGET_REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi

STORE_PASSWORD="${ANDROID_KEYSTORE_STORE_PASSWORD:-}"
KEY_PASSWORD="${ANDROID_KEYSTORE_KEY_PASSWORD:-}"
if [ "${DEBUG_DEFAULTS}" = 1 ]; then
  STORE_PASSWORD="${STORE_PASSWORD:-android}"
  KEY_PASSWORD="${KEY_PASSWORD:-android}"
fi

secret_state() {
  if [ -n "$1" ]; then
    echo "已提供（值隐藏）"
  else
    echo "交互输入"
  fi
}

echo "== GitHub Actions 发版 secrets 配置 =="
echo "仓库    : ${TARGET_REPO}"
echo "keystore: ${KEYSTORE_PATH}"
echo "alias   : ${KEY_ALIAS}"
echo

if [ ! -f "${KEYSTORE_PATH}" ]; then
  echo "错误: keystore 不存在: ${KEYSTORE_PATH}" >&2
  exit 1
fi
if [ ! -r "${KEYSTORE_PATH}" ]; then
  echo "错误: keystore 不可读: ${KEYSTORE_PATH}" >&2
  exit 1
fi
command -v base64 >/dev/null || { echo "错误: 需要 base64 命令" >&2; exit 1; }

if [ "${APPLY}" -eq 0 ]; then
  EXTRA_ARGS=""
  if [ "${DEBUG_DEFAULTS}" = 1 ]; then EXTRA_ARGS="${EXTRA_ARGS} --debug-defaults"; fi
  if [ "${SKIP_TOKEN}" = 1 ]; then EXTRA_ARGS="${EXTRA_ARGS} --skip-token"; fi
  if [ "${SKIP_TOKEN}" = 1 ]; then
    TOKEN_STATE="未设置（--skip-token）"
  else
    TOKEN_STATE="$(secret_state "${APP_API_TOKEN:-}")"
  fi
  cat <<EOF

将写入以下 secrets（脚本不会打印它们的值）:
  ANDROID_KEYSTORE_BASE64         <- base64(keystore 文件内容)
  ANDROID_KEYSTORE_STORE_PASSWORD <- $(secret_state "${STORE_PASSWORD}")
  ANDROID_KEYSTORE_KEY_ALIAS      <- ${KEY_ALIAS}
  ANDROID_KEYSTORE_KEY_PASSWORD   <- $(secret_state "${KEY_PASSWORD}")
  APP_API_TOKEN                   <- ${TOKEN_STATE}

确认后执行:
  bash scripts/setup-gh-release-secrets.sh --apply --keystore '${KEYSTORE_PATH}' --alias '${KEY_ALIAS}'${EXTRA_ARGS}

可选（发版后自动部署到服务器）：设置仓库变量 ENABLE_AUTO_DEPLOY=true，并配置
secrets DEPLOY_HOST / DEPLOY_USER / DEPLOY_SSH_KEY（建议同时配置 DEPLOY_KNOWN_HOSTS）。
EOF
  exit 0
fi

command -v gh >/dev/null || { echo "错误: 需要 gh CLI（https://cli.github.com）" >&2; exit 1; }

if [ -z "${STORE_PASSWORD}" ]; then
  read -r -s -p "keystore 口令（输入不回显）: " STORE_PASSWORD
  echo
fi
if [ -z "${KEY_PASSWORD}" ]; then
  read -r -s -p "key 口令（输入不回显）: " KEY_PASSWORD
  echo
fi
if [ "${SKIP_TOKEN}" -eq 0 ] && [ -z "${APP_API_TOKEN:-}" ]; then
  read -r -s -p "APP_API_TOKEN（输入不回显）: " APP_API_TOKEN
  echo
fi
if [ -z "${STORE_PASSWORD}" ] || [ -z "${KEY_PASSWORD}" ]; then
  echo "错误: keystore 口令 / key 口令不能为空" >&2
  exit 1
fi

echo "写入 secrets 到 ${TARGET_REPO} …"
base64 < "${KEYSTORE_PATH}" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "${TARGET_REPO}"
printf '%s' "${STORE_PASSWORD}" | gh secret set ANDROID_KEYSTORE_STORE_PASSWORD --repo "${TARGET_REPO}"
printf '%s' "${KEY_ALIAS}" | gh secret set ANDROID_KEYSTORE_KEY_ALIAS --repo "${TARGET_REPO}"
printf '%s' "${KEY_PASSWORD}" | gh secret set ANDROID_KEYSTORE_KEY_PASSWORD --repo "${TARGET_REPO}"
if [ "${SKIP_TOKEN}" -eq 0 ]; then
  printf '%s' "${APP_API_TOKEN}" | gh secret set APP_API_TOKEN --repo "${TARGET_REPO}"
fi
echo "secrets 写入完成。"

# 打印签名证书指纹（公开信息，不是密钥），便于可选地设置 ANDROID_EXPECTED_CERT_SHA256 强校验。
if command -v keytool >/dev/null; then
  CERT_SHA256=""
  if KEYSTORE_STORE_PASSWORD_ENV="${STORE_PASSWORD}" keytool -list -v \
      -keystore "${KEYSTORE_PATH}" -alias "${KEY_ALIAS}" -storepass:env KEYSTORE_STORE_PASSWORD_ENV \
      | grep -q 'SHA256:'; then
    CERT_SHA256="$(KEYSTORE_STORE_PASSWORD_ENV="${STORE_PASSWORD}" keytool -list -v \
      -keystore "${KEYSTORE_PATH}" -alias "${KEY_ALIAS}" -storepass:env KEYSTORE_STORE_PASSWORD_ENV 2>/dev/null \
      | awk -F'SHA256: ' '/SHA256:/{print $2; exit}' | tr -d ':')"
  fi
  if [ -n "${CERT_SHA256}" ]; then
    echo
    echo "签名证书 SHA-256（公开信息）: ${CERT_SHA256}"
    echo "可选：设置仓库变量做强校验："
    echo "  gh variable set ANDROID_EXPECTED_CERT_SHA256 --repo ${TARGET_REPO} --body '${CERT_SHA256}'"
  fi
fi
