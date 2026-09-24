#!/usr/bin/env bash
# media_app APK 发布脚本：把 media_app/AGENTS.md 描述的手工发布流程收敛为一条命令。
# 本脚本即 APK 上传命令、manifest 字段与完整部署流程的权威实现（skill media-app-release 引用此处）。
#
# 功能：
#   1. 编译 normal（media_app/）与 legacy（media_app_legacy/）两个 release 变体
#      （已配 debug 签名 + R8 minify，可直接安装）。
#   2. 计算每个 APK 的 SHA256 与大小，按服务器索引约定重命名：
#        media-center-app-v<versionCode>-<sha12>.apk
#      其中 <sha12> 是 APK 文件 SHA256 的前 12 位十六进制（与 workwork admin
#      mediaAppReleaseFilename() 一致，不是 git commit sha）。
#   3. 生成 app-update.json（normal）与 app-update-legacy.json（legacy）两份
#      manifest 到本地输出目录，字段与 media_server/app_update.go 的
#      AppUpdateManifest 解析与校验精确一致：
#        versionCode (>0) / versionName (非空) / apkFilename / sizeBytes (>0) /
#        sha256 (64 位 hex) / releaseNotes (可选) / publishedAt (RFC3339 UTC)
#   4. 可选 --upload 用 scp 上传 APK 与 manifest。
#
# 版本号由 git tag 驱动（构建期 git describe 自动派生，见 media_app build.gradle.kts），
# 发布前先按发版流程打好 v* tag，脚本按同一算法在 shell 侧复算 versionName/versionCode
# 用于 manifest（与 Gradle 构建注入值一致）。
#
# 用法：
#   scripts/release-apk.sh [--upload] [--server user@host] [--remote-dir DIR]
#                          [--release-notes TEXT] [--out DIR]
#                          [--skip-normal] [--skip-legacy] [-h]
#
# 环境变量：
#   JAVA_HOME          缺省时自动使用 Android Studio JBR（macOS 默认路径，需存在）
#   ANDROID_HOME       缺省时自动使用 ~/Library/Android/sdk（存在时）；worktree
#                      无 local.properties，Gradle 依赖此变量或 local.properties 定位 SDK
#   RELEASE_SERVER     --server 未指定时的上传目标（user@host）
#   RELEASE_REMOTE_DIR --remote-dir 未指定时的服务器目标目录
#                      （默认 .wework/generated/media_center，与 workwork 约定一致）
set -euo pipefail

# ---------- 常量与默认值 ----------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DEFAULT_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
DEFAULT_REMOTE_DIR=".wework/generated/media_center"

UPLOAD=0
SERVER="${RELEASE_SERVER:-}"
REMOTE_DIR="${RELEASE_REMOTE_DIR:-$DEFAULT_REMOTE_DIR}"
RELEASE_NOTES=""
OUT_DIR="${REPO_ROOT}/build/release-apk"
SKIP_NORMAL=0
SKIP_LEGACY=0

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upload) UPLOAD=1; shift ;;
    --server) SERVER="${2:?--server 需要 user@host}"; shift 2 ;;
    --remote-dir) REMOTE_DIR="${2:?--remote-dir 需要目录}"; shift 2 ;;
    --release-notes) RELEASE_NOTES="${2:?--release-notes 需要文本}"; shift 2 ;;
    --out) OUT_DIR="${2:?--out 需要目录}"; shift 2 ;;
    --skip-normal) SKIP_NORMAL=1; shift ;;
    --skip-legacy) SKIP_LEGACY=1; shift ;;
    -h|--help) usage ;;
    *) echo "未知参数: $1（-h 查看用法）" >&2; exit 2 ;;
  esac
done

log()  { echo "[release-apk] $*"; }
die()  { echo "[release-apk] 错误: $*" >&2; exit 1; }

# ---------- 前置检查 ----------

command -v git >/dev/null || die "需要 git"
command -v python3 >/dev/null || die "需要 python3（用于生成 manifest JSON）"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "${DEFAULT_JAVA_HOME}" ]]; then
    JAVA_HOME="${DEFAULT_JAVA_HOME}"
    log "未设置 JAVA_HOME，使用 Android Studio JBR: ${JAVA_HOME}"
  else
    die "未设置 JAVA_HOME 且默认路径不存在: ${DEFAULT_JAVA_HOME}（可用 mdfind 定位 JBR 后显式设置）"
  fi
fi

# ANDROID_HOME 兜底：worktree / 新克隆没有 local.properties（被 gitignore），
# Gradle 定位不到 SDK 会直接失败；未设置且本机默认 SDK 存在时自动补上。
if [[ -z "${ANDROID_HOME:-}" ]]; then
  DEFAULT_ANDROID_HOME="${HOME}/Library/Android/sdk"
  if [[ -d "${DEFAULT_ANDROID_HOME}" ]]; then
    ANDROID_HOME="${DEFAULT_ANDROID_HOME}"
    export ANDROID_HOME
    log "未设置 ANDROID_HOME，使用本机 SDK: ${ANDROID_HOME}"
  fi
  # 默认路径也不存在时不阻断：主工作树的 local.properties 仍可生效，交给 Gradle 处理
fi

if [[ "${UPLOAD}" -eq 1 && -z "${SERVER}" ]]; then
  die "--upload 需要指定 --server user@host（或 RELEASE_SERVER 环境变量）"
fi

# SHA256 工具探测（macOS 无 sha256sum，用 shasum -a 256）
if command -v sha256sum >/dev/null; then
  sha256_of() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null; then
  sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  die "需要 sha256sum 或 shasum 计算摘要"
fi

# 字节数（可移植：不依赖 stat 的 BSD/GNU 差异）
size_of() { wc -c < "$1" | tr -d ' '; }

cd "${REPO_ROOT}"

# ---------- 版本派生（复刻 build.gradle.kts 的 gitVersionName/semverToCode） ----------

# git describe 产出: v1.1.0-0-gabc（恰在 tag）或 v1.1.0-3-gabc（tag 后 3 个 commit）；
# 无匹配 tag 时 --always 回退为纯短 sha，与 Gradle 侧一致落回 0.1.0-dev。
version_name() {
  local described
  described="$(git describe --tags --match 'v[0-9]*' --long --always 2>/dev/null || echo "0.1.0-dev-0")"
  if [[ "${described}" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)-([0-9]+)-g[0-9a-f]+$ ]]; then
    local semver="${BASH_REMATCH[1]}" count="${BASH_REMATCH[2]}"
    if [[ "${count}" -eq 0 ]]; then
      echo "${semver}"
    else
      echo "${semver}-dev.${count}"
    fi
  else
    echo "0.1.0-dev"
  fi
}

# versionCode = major*100000 + minor*1000 + patch（忽略 -dev.N 后缀）
version_code() {
  local name="$1" semver major minor patch
  semver="${name%%-*}"
  IFS='.' read -r major minor patch <<< "${semver}"
  echo $((10#${major} * 100000 + 10#${minor} * 1000 + 10#${patch}))
}

# ---------- 构建与产物处理 ----------

# build_variant <src-dir> <variant-name>
# 产物落 OUT_DIR，manifest 写 OUT_DIR/app-update[-legacy].json
build_variant() {
  local src_dir="$1" variant="$2"
  local app_dir="${REPO_ROOT}/${src_dir}"
  [[ -x "${app_dir}/gradlew" ]] || die "${src_dir}/gradlew 不存在或不可执行"

  log "编译 ${variant} 变体（${src_dir}，:app:assembleRelease --no-daemon）…"
  (cd "${app_dir}" && JAVA_HOME="${JAVA_HOME}" ./gradlew :app:assembleRelease --no-daemon)

  local built_apk="${app_dir}/app/build/outputs/apk/release/app-release.apk"
  [[ -f "${built_apk}" ]] || die "未找到构建产物: ${built_apk}"

  local vname vcode sha size apk_name manifest_name
  vname="$(version_name)"
  vcode="$(version_code "${vname}")"
  sha="$(sha256_of "${built_apk}")"
  size="$(size_of "${built_apk}")"
  apk_name="media-center-app-v${vcode}-${sha:0:12}.apk"

  if [[ "${variant}" == "normal" ]]; then
    manifest_name="app-update.json"
  else
    manifest_name="app-update-${variant}.json"
  fi

  cp "${built_apk}" "${OUT_DIR}/${apk_name}"
  write_manifest "${OUT_DIR}/${manifest_name}" "${vcode}" "${vname}" "${apk_name}" "${size}" "${sha}" "${RELEASE_NOTES}"
  log "${variant}: ${apk_name}（versionName=${vname} versionCode=${vcode} size=${size} sha256=${sha:0:12}…）"
  log "${variant}: manifest → ${OUT_DIR}/${manifest_name}"
}

# write_manifest <path> <versionCode> <versionName> <apkFilename> <sizeBytes> <sha256> <releaseNotes>
# JSON 经 python3 序列化，字段名与 media_server AppUpdateManifest 的 json tag 一致。
# 发布说明为空时不写 releaseNotes（Go 侧 omitempty 可选字段）。
write_manifest() {
  local path="$1" vcode="$2" vname="$3" apk_name="$4" size="$5" sha="$6" notes="$7"
  python3 - "${path}" "${vcode}" "${vname}" "${apk_name}" "${size}" "${sha}" "${notes}" <<'PY'
import json, sys
from datetime import datetime, timezone

path, vcode, vname, apk_name, size, sha, notes = sys.argv[1:8]
manifest = {
    "versionCode": int(vcode),
    "versionName": vname,
    "apkFilename": apk_name,
    "sizeBytes": int(size),
    "sha256": sha,
}
if notes:
    manifest["releaseNotes"] = notes
manifest["publishedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
with open(path, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
}

# 校验派生版本与 Go 侧 validateAppUpdateManifest 同规（versionCode>0、versionName 非空）
VNAME="$(version_name)"
VCODE="$(version_code "${VNAME}")"
[[ "${VCODE}" -gt 0 ]] || die "派生 versionCode 非法: ${VCODE}（versionName=${VNAME}）"
log "版本: versionName=${VNAME} versionCode=${VCODE}（git tag 驱动）"

mkdir -p "${OUT_DIR}"

[[ "${SKIP_NORMAL}" -eq 1 ]] || build_variant "media_app" "normal"
[[ "${SKIP_LEGACY}" -eq 1 ]] || build_variant "media_app_legacy" "legacy"

# ---------- 可选上传 ----------

if [[ "${UPLOAD}" -eq 1 ]]; then
  log "上传 APK 与 manifest → ${SERVER}:${REMOTE_DIR}/"
  # 服务器索引约定：版本化 APK 文件名 + 两份 manifest
  scp "${OUT_DIR}"/media-center-app-v*.apk "${SERVER}:${REMOTE_DIR}/"
  scp "${OUT_DIR}/app-update.json" "${OUT_DIR}/app-update-legacy.json" "${SERVER}:${REMOTE_DIR}/"
  log "上传完成。服务器侧需按 workwork README 完成发布确认（或经 admin 面板发布）。"
fi

log "全部完成。产物目录: ${OUT_DIR}"
