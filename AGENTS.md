# media_center

合规公开视频媒体中心：Android TV 客户端（`media_app`）+ Go 聚合后端（`media_server`）+ 独立点播解析服务（`vod_extractor`，为 `youtube` provider 提供播放直链解析）。

通用约束（Git 规范、Commit 格式、验证流程、工具链偏好、JDK 配置、Docker 等）以全局 `~/AGENTS.md` 为准（各 AI 工具的全局指令文件均链接到该文件），本文件只做项目特有补充，不得放宽或违背全局约束。

本目录是 `media_center` 仓库（`ShanWuYinShe/media_center`）。

## 仓库结构

- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端
- `media_app_legacy/` — API 19 原生 View/RecyclerView 兼容客户端（老电视/盒子，与新版保持同一 UI 语义）
- `media_server/` — Go 目录服务（provider 列表、分类列表、视频元数据）+ youtube provider 的实时通道（yt-dlp ytsearch 实时搜索，无 Data API 配额；vod_extractor 解析代理，见 `media_server/youtube.go`）。
- `vod_extractor/` — Python + yt-dlp 独立点播解析服务（YouTube 播放直链解析）。`media_server` 经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 调用其 `POST /search`（实时搜索）与 `POST /extract`（播放直链解析）。
  - **硬前提**：YouTube 对数据中心 IP 的**播放解析**触发 "Sign in to confirm you're not a bot"，故解析必须放置 cookies；**搜索**走 ytsearch flat 列表页不受影响，无 cookies 仍可用（复核：`POST /search` 返回 200；`GET /health` 的 `failures.counts` 中 `bot_check` 不增长）。cookies 路径与挂载点、`VOD_EXTRACTOR_EXTRACTOR_ARGS`、过期重导出等运维细节以 `vod_extractor/AGENTS.md`「部署硬前提」为唯一维护处，此处不再复制。
- `deploy/` — 独立站编排（media.chaoset.com）：nginx + media_server + vod_extractor 三服务自洽 compose，版本唯一来源 `deploy/versions.env`；操作细节见 `deploy/README.md`。

App 只配置 `MEDIA_SERVER_URL` 一个服务端地址，不关心服务端部署方式。构建期未设置时回落到内置默认 `https://media.chaoset.com/api/v1`（独立站规范前缀）。旧前缀 `https://yinghuoren.com/api/media`（workwork 聚合栈过渡别名）**不由 workwork 反代**（`workwork/services/admin/configs/nginx.conf.tmpl` 注释明确「本机不再反代，旧路径落到 404 兜底」；`media_server` 代码里无 `/api/media` 前缀处理），由 **CF 边缘 308 重定向**到 `media.chaoset.com/api/*`；排障时查 CF 规则，不要查 nginx。

## CI 门禁与本地验证分工

重活（全量测试、构建、打包、E2E、镜像构建）一律交给 GitHub Actions，本地只跑秒级检查。
完整流程见全局 skill `github-ci-flow`：开发分支推送 → `gh pr create` → `gh pr checks --watch`
全绿 → `gh pr merge --rebase --delete-branch` → 本地 `git pull --ff-only` 并清理 worktree。

- 本地（提交前，秒级）：
  - Go：`gofmt -l media_server/`（应无输出）+ `cd media_server && go build ./... && go vet ./...`（本机实测约 6s / 2s）。
  - Python：`python3 -m py_compile vod_extractor/server.py`（秒级）；本机未装 ruff 时交 CI（CI 固定 `ruff==0.16.8`）。
  - Android：日常不跑 Gradle（`assembleDebug`/`assembleRelease`、单测、lint 均由 CI 执行）；APK 发版由 CI 在 tag 上构建（见「APK 发版（GitHub Actions）」）。
  - **不跑**：`go test ./...`（全量/含 `-race`）、Gradle 构建与单测、`docker build`。
- CI 覆盖（`.github/workflows/ci.yml`，`push: main` + `pull_request: main`，共 3 个 job）：
  - `Media Server (Go)`：gofmt、go vet、go build、`go test -race -count=1 ./...`。
  - `VOD Extractor (Python)`：`py_compile`、ruff 0.16.8、unittest。
  - `Media App (Android)`：normal 与 legacy 均编译 `assembleDebug` + `assembleRelease`（R8/资源压缩问题在 PR 阶段暴露）、`testDebugUnitTest`（normal）、`lintDebug`（normal）；release APK 作为 CI artifact 保留 7 天，供 TV 真机走查（未注入令牌、临时 debug 签名，不可发版）。
- CI 覆盖不到、必须本地/真机验证的：Android TV 真机/模拟器安装与 UI 走查（APK 部署与 TV UI/UX 约定见 `media_app/AGENTS.md` 与 skill `media-app-release`/`media-app-tv-ui`）；youtube 播放解析的真实链路依赖服务器 cookies/PO Token，CI 只跑单测，改动该链路时需在服务器侧实测（见 `vod_extractor/AGENTS.md`）。
- 镜像构建：本仓 CI 无镜像构建 job；`deploy/` 生产镜像由部署服务器 `make build` 构建（交付链路，不是开发机本地验证），本地不做 `docker build`。
- 发版/发布门禁：media_app APK 发版由 GitHub Actions 在 tag 上构建发布（见下节）；服务端部署按 `deploy/README.md`。

## APK 发版（GitHub Actions）

`.github/workflows/release-app.yml` 是 APK 发版的权威入口：

- **触发**：push annotated tag `vX.Y.Z`（tag 必须在 main 的合并提交上）→ 构建 normal + legacy release APK、生成两份 `app-update*.json` 与 `SHA256SUMS`、创建/更新 GitHub Release；`workflow_dispatch` 只做构建校验（上传 Actions artifact，不发布、不部署）。
- **必填 secrets**（tag 发版缺失会直接失败，避免产出 YouTube 401 或签名不稳定的包）：`APP_API_TOKEN`、`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_STORE_PASSWORD`、`ANDROID_KEYSTORE_KEY_ALIAS`、`ANDROID_KEYSTORE_KEY_PASSWORD`。一次性写入：`scripts/setup-gh-release-secrets.sh --apply`（不打印密钥；`--debug-defaults` 适用于 Android 标准 debug keystore）。
- **签名必须稳定**：APK 签名密钥不变才能原位升级；沿用现行发版 keystore（推荐）即可无缝升级，换新密钥后老用户需卸载重装。
- **可选 variables**：`MEDIA_SERVER_URL`（构建期服务端基址，留空用 Gradle 默认）、`ANDROID_EXPECTED_CERT_SHA256`（签名证书 SHA-256 强校验，配错会让发版失败）、`ENABLE_AUTO_DEPLOY`（`true` 时发版后自动部署）、`DEPLOY_REMOTE_DIR`（默认 `media_center/deploy/data/generated`）。
- **可选自动部署 secrets**：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_KNOWN_HOSTS`（建议必填，否则回退 ssh-keyscan）；只写 APK + 两份 manifest，media_server 热加载、无需重启，旧 APK 自动保留最近 5 个。
- **本地构建仍可临时用**：`scripts/release-apk.sh`（不带 `--upload`）仅作调试手段；正式发版以 tag 触发的 Actions 产物为准。

## media_app（详见 media_app/AGENTS.md 与 skill）

构建、版本号（git tag 驱动）、APK 部署与 TV UI/UX 设计约定以 `media_app/AGENTS.md` 为唯一维护处。其中发版流程另见 skill media-app-release，TV UI/UX 约定另见 skill media-app-tv-ui。

## media_server（详见 media_server/AGENTS.md 与 skill）

构建/测试/格式检查命令与 catalog 审核体系（唯一质量关口、youtube 实时搜索受控例外）以 `media_server/AGENTS.md` 为唯一维护处。catalog 审核体系另见 skill media-server-catalog。

- 播放解析（跨端契约）：App 端按 provider 处理——bilibili 用 `BilibiliApi`（WBI 签名 + view + playurl）本地解析；youtube 走 server 端解析（`/api/v1/providers/youtube/resolve` 代理 `vod_extractor`，App 拿到全部清晰度档后在播放器内本地换源）。解析集中在服务端的原因：YouTube 风控对抗依赖 yt-dlp 及时更新，服务器容器重建即可全端生效，TV App 无需发版。
- 部署：独立站 `deploy/`（compose，三服务自洽）。

## API 约定

以本仓库 `media_server/API.md` 为准。业务接口规范前缀 `/api/v1`（如 `/api/v1/providers`），旧 `/api/*` 为过渡期废弃别名，两者行为一致。App 侧目录数据消费 providers/{provider}/bootstrap（首页启动聚合：providers + 该源分类一次返回）、providers、providers/{provider}/categories、providers/{provider}/categories/{catID}/videos 接口；另消费 App 更新接口 `/api/v1/app/update`、`/api/v1/app/download`、凭证接口 `/api/v1/providers/credentials`；youtube provider 额外消费实时端点 `/api/v1/providers/youtube/search` 与 `/api/v1/providers/youtube/resolve`（均要求 `X-App-Token`，见 `media_server/youtube.go` 与 API.md）。`GET /healthz` 为无版本前缀的存活探针。
播放由 App 端收到 `VideoSummary.id`（nativeId）后按 provider 处理：bilibili 本地解析、youtube 调 server 解析端点。详情页由 App 端本地从 `VideoSummary` 构造，不调 server。
`VideoSummary` 有 `id/title/description/thumbnailUrl/thumbnailUrlLarge/durationSeconds/year` 七个字段；`thumbnailUrl` 是卡片尺寸、`thumbnailUrlLarge` 是详情页大图（未知图床两者相同，旧客户端只读前者行为不变），`year` 仅目录数据有、youtube 实时搜索可能省略。

## 约束

- 视频类型：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- 合规：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- 服务端变更：`media_server/` 维护 catalog 数据模型和查询逻辑；App 端播放解析在 `media_app/` 维护。
