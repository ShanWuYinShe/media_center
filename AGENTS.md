# media_center

合规公开视频媒体中心：Android TV 客户端（`media_app`）+ Go 聚合后端（`media_server`）+ 独立点播解析服务（`vod_extractor`，为 `youtube` provider 提供播放直链解析）。

通用约束（Git 规范、Commit 格式、验证流程、工具链偏好、JDK 配置、Docker 等）以全局 `~/AGENTS.md` 为准（各 AI 工具的全局指令文件均链接到该文件），本文件只做项目特有补充，不得放宽或违背全局约束。

 本目录是 `media_center` 仓库（`ShanWuYinShe/media_center`），被 `workwork` 仓库以 git submodule 引用（`services/media_center`）。

AI Agent 工作目录：所有 AI agent 工作期间产生的中间文件写入项目根 `.workwork/`（已 `.gitignore`）。

## 仓库结构

- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端
- `media_server/` — Go 目录服务（provider 列表、分类列表、视频元数据）+ youtube provider 的实时通道（Data API 搜索、vod_extractor 解析代理，见 `media_server/youtube.go`）。
- `vod_extractor/` — Python + yt-dlp 独立点播解析服务（YouTube 播放直链解析）。由 `workwork` compose 部署在 internal_docker 内网，`media_server` 经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 调用其 `POST /extract`。
  - **硬前提**：YouTube 对数据中心 IP 的所有 player_client 都触发 "Sign in to confirm you're not a bot"，服务器部署必须放置 YouTube cookies（Netscape 格式）到 `.wework/vod_extractor/youtube-cookies.txt`（容器只读挂载为 `/config/youtube-cookies.txt`），否则解析与搜索一律失败。
  - 部署该服务时优先通过 `VOD_EXTRACTOR_EXTRACTOR_ARGS`（如 `youtube:player_client=tv`）与 cookies 双管齐下；cookies 过期后需重新导出。

App 只配置 `MEDIA_SERVER_URL` 一个服务端地址，不关心服务端部署方式。默认服务端 URL 是 `https://yinghuoren.com/api/media`。代理 / 部署基础设施在 `workwork` 仓库，通过 git submodule 引用本仓库来构建 `media_server` 和 `vod_extractor`。

## media_app

```bash
cd media_app
# JAVA_HOME 见全局「本机环境」Android Studio JBR 条目（以 ls 验证，变动时用 mdfind 定位）
# --no-daemon 必加：Gradle/Kotlin Daemon 默认构建后 idle 保留数小时不退出（各 -Xmx2g），
# AI agent 多次构建会累积多个 java 进程撑爆内存。--no-daemon 让构建完 java 进程立即退出。
# release（非 debug）：已配 debug 签名（signingConfig=debug）+ R8 minify，体积减半且可直接安装。
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease --no-daemon
# 产物: app/build/outputs/apk/release/app-release.apk
```

- 部署 APK：部署时如 `media_app/` 有变更，编译 APK 并上传到服务器的 `.wework/generated/media_center/media-center-app.apk`，同时生成 manifest JSON。
  - 版本号规范（git tag 驱动，构建期自动读取，禁止手改 `build.gradle`）：`versionName` / `versionCode` 由 `build.gradle.kts` 在构建期用 `git describe` 读取最近 `v*` tag 自动派生，绝不手动改 versionName/versionCode。
    - `versionName`：取 tag（`v1.1.0` → `1.1.0`）；tag 后有新 commit 则带 `-dev.N` 后缀（如 `1.1.0-dev.3`）标识未发布构建。
    - `versionCode`：从 SemVer 派生 `major*100000 + minor*1000 + patch`（见 `semverToCode`，忽略 dev 后缀），整数比较可靠。容量：patch 0..999、minor 0..99。
    - 升版规则：破坏性改动升 major、新功能升 minor、bug 修复升 patch。无 tag 时 fallback 到 `0.1.0-dev`（不阻断构建）。
  - AI 更新版本流程（代码改完、发版前执行）：版本号只由 git tag 决定，打完 tag 即“自动更新版本”，无需改任何文件。commit / tag 在对应 Worktree 内完成；合并进主分支后，push（含 tag）在主工作树执行（见全局 Git 准则）。
    1. 在 Worktree 内提交代码变更（确保要发布的 commit 已就绪）。
    2. 按变更类型决定下一 SemVer：`major`（破坏性）/`minor`（新功能）/`patch`（bug 修复）。
    3. 合并进主分支后，在主工作树于当前 commit 打 tag 并推送：`git tag vX.Y.Z && git push origin vX.Y.Z && git push origin main`（tag 必须在要发布的 commit 上）。
    4. 随后构建（`./gradlew :app:assembleRelease --no-daemon`）即得到干净版本号（`git describe` 在 tag 上产出 `X.Y.Z`，无 `-dev.N`）。
    注意：tag 要打在 media_center 仓库（非外层 `workwork` 仓库）；版本号与外层仓库的提交/tag 无关。
  - SHA256 校验和自动计算
  - 命令：`ssh workwork-server "cd service && cat > .wework/generated/media_center/app-update.json << 'EOF' { ... } EOF"`
- 技术栈：Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 服务端地址：默认 `https://yinghuoren.com/api/media`；如需覆盖，设置 `MEDIA_SERVER_URL`。

## media_server

```bash
go build -C media_server -o /dev/null .   # 编译检查
go vet -C media_server ./...
go test -C media_server ./...
```

- 技术栈：Go；HTTP 服务监听 `PORT`（容器内默认 8787）。
- 内容分类：`media_server/src/contentCategories.json`；线上由 `workwork` 的 admin 生成运行时分类配置注入。
- 分类数据（catalog，SQLite，system-of-record）：分类视频列表由 `media_server/catalog.go` 用纯 Go 的 `modernc.org/sqlite`（无需 cgo）落地维护库；分类浏览只读该库的 `approved` 数据（见 `catalog_serve.go` 与 `catalog.go` 的 `SearchApproved`）；没有 `approved` 数据时返回空列表 / 空搜索结果。
  - 候选不提交仓库：候选（外部 / AI 收集汇总）是中间状态，不纳入版本管理；最终数据以 catalog.db（system-of-record）为准，可通过 `/internal/catalog/export` 导出。各分类的收集格式与审核标准见 `media_server/CATEGORY_CURATION_GUIDE.md`。
  - 流程：外部收集候选 → 导入 pending → 人工 + AI 审核 → 上线 approved。候选经 `/internal/catalog/import` 导入为 `pending`（重复导入只新增，绝不覆盖已有审核决定）；在 admin 页面由人工 + AI 模型审核 approve / reject，可选 `pinned` / `rank`（经 `/internal/catalog/*`，也可用 `-catalog-review` 交互命令或任意 SQLite 工具）；客户端的分类浏览与搜索只命中该库 `approved` 清单（顺序 `pinned` > `rank` > `score`）。
  - 审核是唯一的质量关口：聚合视频未经审核普遍标题与内容不符、大量无法播放，相关性、可播放性等筛选一律交给 admin 的人工 + AI 审核。**youtube 实时搜索是这个原则的受控例外**：`/api/providers/youtube/search` 直通 Data API 只为 App 即时展示/播放，结果不写 catalog、不进分类列表，审核关卡不被绕过。
- 只存原始数据：catalog 只存上游原始字段(缩略图为原始地址)。
- 数据库文件不提交:catalog 库写到 `MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,含 `-wal`/`-shm` 旁文件,已 gitignore);线上由 `workwork` 挂卷生成。
  - 相关环境变量:`MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,置空则关闭 catalog,分类浏览与搜索返回空)。
- 播放解析：App 端按 provider 处理——bilibili 用 `BilibiliApi`（WBI 签名 + view + playurl）本地解析；youtube 走 server 端解析（`/api/providers/youtube/resolve` 代理 `vod_extractor`，App 拿到全部清晰度档后在播放器内本地换源）。解析集中在服务端的原因：YouTube 风控对抗依赖 yt-dlp 及时更新，服务器容器重建即可全端生效，TV App 无需发版。
- 部署：`workwork` 以 git submodule 引用本仓库，docker-compose 从 `services/media_center/media_server` 构建。

## UI/UX 设计约定

- 固定交互控件尺寸：按钮、chip、圆形 icon button 等可聚焦/可点击控件使用 `requiredHeight` / `requiredSize` 保证可操作面积，不用普通 `height` / `size` 让父布局压缩。
- 长文本不可挤压操作区：标题、简介、错误文案、分类名、按钮文案必须设置 `maxLines` 和 `TextOverflow.Ellipsis`。详情页这类"文案 + 主操作"布局中，主操作区优先保留空间；长简介先折叠，超出后用"查看详情"弹层展示完整内容，不能在原布局内无限展开。
- 详情展开弹层：详情页简介超出折叠行数时使用紧凑"查看完整简介"披露按钮；完整简介在独立 Dialog 阅读卡片中展示，保留 BACK 关闭路径和主按钮焦点，避免在详情页原布局内撑开。
- 图标风格统一：播放、暂停、搜索、清除、箭头等图标使用 Canvas/vector/组件绘制，不把 `▶`、`⏸`、emoji 等 Unicode 字符当图标拼进 `Text`，避免 Android 字体 fallback 导致风格不一致。
- TV 焦点态一致：焦点态统一使用 Apple Blue 背景、白色文字/图标、`GlassBorderFocused` 近白外框；selected 只表达当前分类/选项，不应强过焦点态。焦点切换应使用明确的 `FocusRequester` / `focusProperties` 路径，避免依赖空间猜测。
- Back 行为稳定：详情页、播放页、搜索面板等屏幕级返回使用 `BackHandler`，不要依赖子组件焦点或 `onPreviewKeyEvent` 捕获 Back。
- Apple TV 视觉基线：OLED 深色背景、玻璃灰卡片、Apple Blue 作为唯一强调色；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。

## API 约定

以本仓库 `media_server/API.md` 为准。App 侧目录数据只消费 `/api/providers`、`/api/providers/{provider}/categories`、`/api/providers/{provider}/categories/{catID}/videos` 三个接口；另消费 App 更新接口 `/api/app/update`、`/api/app/download`、凭证接口 `/api/providers/credentials`；youtube provider 额外消费实时端点 `/api/providers/youtube/search` 与 `/api/providers/youtube/resolve`（均要求 `X-App-Token`，见 `media_server/youtube.go` 与 API.md）。
播放由 App 端收到 `VideoSummary.id`（nativeId）后按 provider 处理：bilibili 本地解析、youtube 调 server 解析端点。详情页由 App 端本地从 `VideoSummary` 构造，不调 server。
`VideoSummary` 只有 `id/title/description/thumbnailUrl/durationSeconds` 五个字段。

## 约束

- 视频类型：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- 合规：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- 服务端变更：`media_server/` 维护 catalog 数据模型和查询逻辑；App 端播放解析在 `media_app/` 维护。
