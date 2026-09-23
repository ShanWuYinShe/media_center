# media_center

合规公开视频媒体中心：Android TV 客户端（`media_app`）+ Go 聚合后端（`media_server`）+ 独立点播解析服务（`vod_extractor`，为 `youtube` provider 提供播放直链解析）。

通用约束（Git 规范、Commit 格式、验证流程、工具链偏好、JDK 配置、Docker 等）以全局 `~/AGENTS.md` 为准（各 AI 工具的全局指令文件均链接到该文件），本文件只做项目特有补充，不得放宽或违背全局约束。

本目录是 `media_center` 仓库（`ShanWuYinShe/media_center`）。

## 按需加载的 skill（.agents/skills/）

| skill | 何时加载 |
|---|---|
| media-app-release | 发版、打 tag、构建与部署 APK 时 |
| media-app-tv-ui | 新增或修改 TV 界面与交互时 |
| media-server-catalog | 改分类数据、审核流程、搜索与解析时 |

## 仓库结构

- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端
- `media_app_legacy/` — 旧版客户端存档（不再开发）
- `media_server/` — Go 目录服务（provider 列表、分类列表、视频元数据）+ youtube provider 的实时通道（yt-dlp ytsearch 实时搜索，无 Data API 配额；vod_extractor 解析代理，见 `media_server/youtube.go`）。
- `vod_extractor/` — Python + yt-dlp 独立点播解析服务（YouTube 播放直链解析）。`media_server` 经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 调用其 `POST /search`（实时搜索）与 `POST /extract`（播放直链解析）。
  - **硬前提**：YouTube 对数据中心 IP 的所有 player_client 都触发 "Sign in to confirm you're not a bot"，服务器部署必须放置 YouTube cookies（Netscape 格式）到部署目录 `deploy/data/vod/youtube-cookies.txt`（容器只读挂载为 `/config/youtube-cookies.txt`），否则解析与搜索一律失败。
  - 部署该服务时优先通过 `VOD_EXTRACTOR_EXTRACTOR_ARGS`（如 `youtube:player_client=tv`）与 cookies 双管齐下；cookies 过期后需重新导出。
- `deploy/` — 独立站编排（media.chaoset.com）：nginx + media_server + vod_extractor 三服务自洽 compose，版本唯一来源 `deploy/versions.env`；操作细节见 `deploy/README.md`。

App 只配置 `MEDIA_SERVER_URL` 一个服务端地址，不关心服务端部署方式。默认服务端 URL 正在从 `https://yinghuoren.com/api/media`（workwork 聚合栈过渡别名）迁移到独立站 `https://media.chaoset.com/api/v1`；两种前缀在服务端行为一致（见 `media_server/API.md` 路径前缀约定）。

## media_app（详见 media_app/AGENTS.md 与 skill）

构建、版本号（git tag 驱动）、APK 部署与 TV UI/UX 设计约定以 `media_app/AGENTS.md` 为唯一维护处。其中发版流程另见 skill media-app-release，TV UI/UX 约定另见 skill media-app-tv-ui。

## media_server（详见 media_server/AGENTS.md 与 skill）

构建/测试/格式检查命令与 catalog 审核体系（唯一质量关口、youtube 实时搜索受控例外）以 `media_server/AGENTS.md` 为唯一维护处。catalog 审核体系另见 skill media-server-catalog。

- 播放解析（跨端契约）：App 端按 provider 处理——bilibili 用 `BilibiliApi`（WBI 签名 + view + playurl）本地解析；youtube 走 server 端解析（`/api/v1/providers/youtube/resolve` 代理 `vod_extractor`，App 拿到全部清晰度档后在播放器内本地换源）。解析集中在服务端的原因：YouTube 风控对抗依赖 yt-dlp 及时更新，服务器容器重建即可全端生效，TV App 无需发版。
- 部署：独立站 `deploy/`（compose，三服务自洽）。

## API 约定

以本仓库 `media_server/API.md` 为准。业务接口规范前缀 `/api/v1`（如 `/api/v1/providers`），旧 `/api/*` 为过渡期废弃别名，两者行为一致。App 侧目录数据只消费 providers、providers/{provider}/categories、providers/{provider}/categories/{catID}/videos 三个接口；另消费 App 更新接口 `/api/v1/app/update`、`/api/v1/app/download`、凭证接口 `/api/v1/providers/credentials`；youtube provider 额外消费实时端点 `/api/v1/providers/youtube/search` 与 `/api/v1/providers/youtube/resolve`（均要求 `X-App-Token`，见 `media_server/youtube.go` 与 API.md）。`GET /healthz` 为无版本前缀的存活探针。
播放由 App 端收到 `VideoSummary.id`（nativeId）后按 provider 处理：bilibili 本地解析、youtube 调 server 解析端点。详情页由 App 端本地从 `VideoSummary` 构造，不调 server。
`VideoSummary` 只有 `id/title/description/thumbnailUrl/durationSeconds` 五个字段。

## 约束

- 视频类型：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- 合规：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- 服务端变更：`media_server/` 维护 catalog 数据模型和查询逻辑；App 端播放解析在 `media_app/` 维护。
