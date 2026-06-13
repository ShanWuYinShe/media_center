# CLAUDE.md

合规公开视频媒体中心：Android TV 客户端（`media_app`）+ Go 聚合后端（`media_server`）+ 内部点播解析服务（`vod_extractor`）。

## 仓库结构

- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端
- `media_server/` — Go 实现的合规公开视频聚合后端（分类、搜索、详情、播放地址、asset 代理）
- `vod_extractor/` — Python + yt-dlp 内部点播解析服务，只供 `media_server` 在 Docker 内网调用

App 只配置 `MEDIA_SERVER_URL` 一个服务端地址，不关心服务端部署方式。默认服务端 URL 是 `https://yinghuoren.com/api/media`。代理 / 部署基础设施在 `workwork` 仓库，通过 git submodule 引用本仓库来构建 `media_server` 和 `vod_extractor`。

## media_app

```bash
cd media_app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

- **技术栈**：Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- **服务端地址**：默认 `https://yinghuoren.com/api/media`；如需覆盖，设置 `MEDIA_SERVER_URL`，可以是服务根地址，也可以直接带 API 路径。
- **Gradle/JDK**：本机已安装 Android Studio，Gradle 构建优先使用 Android Studio 自带 JBR：`/Applications/Android Studio.app/Contents/jbr/Contents/Home`。如果系统 `java` 提示缺少 Java Runtime，不要下载或安装 JDK，直接按上面的 `JAVA_HOME=... ./gradlew ...` 方式执行。

## media_server

```bash
go build -C media_server -o /dev/null .   # 编译检查
go vet -C media_server ./...
go test -C media_server ./...
```

- **技术栈**：Go；HTTP 服务监听 `PORT`（容器内默认 8787）。
- **内容分类**：`media_server/src/contentCategories.json`；线上由 `workwork` 的 admin 生成运行时分类配置注入。
- **分类快照（预聚合 + 定期刷新 + 本地审校）**：分类视频列表由 `media_server` 预先聚合成“快照”落盘，请求直接从快照分页返回（秒回、稳定），后台按间隔定期重建；快照缺失/为空时回退实时聚合，保证不出现空页。详见 `media_server/snapshot.go`、`curation.go`。
  - **离线生成 + 人工审校**：`go run -C media_server . -build-snapshots` 在本地把各分类先搜好落盘后退出，便于人工检查再上线。
  - **本地审校 `media_server/src/curation.json`**（提交进仓库）：按分类配置 `blockIds`（按视频 ID 永久剔除）、`blockTitleContains`（按标题关键词剔除）、`pinIds`（精品强制置顶/收录），构建快照时套用，保证结果更精确。审校/置顶**推荐用稳定原生 ID**（`provider:nativeID`，如 `bilibili:BV1xxx`，或裸 BV 号），不随签名密钥变化；也兼容接口返回的公开签名 id。
  - **分类判定与维护指南**：`media_server/CATEGORY_CURATION_GUIDE.md` 详述“一个视频能否加入某分类”的判定标准（与 `relevanceScore` 一致）、视频 ID 约定、推荐的 SQLite 维护库结构，以及下方“目录(catalog)”命令的完整使用流程，供外部工具/人工维护分类数据。
  - **分类维护数据库 catalog（SQLite，system-of-record）**：`media_server/catalog.go` 用纯 Go 的 `modernc.org/sqlite`（无需 cgo）落地维护库。流程：`go run -C media_server . -catalog-seed` 把各分类实时聚合结果灌成 `pending` 候选（重复 seed 只新增、不覆盖已有审校决定）→ 用 `-catalog-review` 子命令（交互或 `-catalog-review-auto` 按分数批量）把候选改 `approved`/`rejected`、可选设 `pinned`/`rank`（也可用任意 SQLite 工具直接改库）→ 构建快照时**目录优先(catalog-first)**：某分类有 `approved` 行就直接发库里的固定清单（顺序 `pinned`>`rank`>`score`，仅再套 curation.json 的 block 剔噪、不重排），没有则回退实时聚合 + curation.json。打开失败时优雅降级为实时聚合，不影响可用性。
  - **快照只存原始数据**：快照落盘的是上游原始字段（缩略图为原始地址），签名 `/asset` 代理 URL 在每次请求时再生成，绝不把 sig/token 写进快照文件。维护库 catalog 同样只存原始字段。
  - **快照/数据库文件不提交**：运行时快照写到 `MEDIA_SERVER_SNAPSHOT_DIR`（默认 `data/snapshots`，已 gitignore）；catalog 库写到 `MEDIA_SERVER_CATALOG_DB`（默认 `data/catalog.db`，含 `-wal`/`-shm` 旁文件，已 gitignore）；线上由 `workwork` 挂卷 + 后台刷新生成。
  - **相关环境变量**：`MEDIA_SERVER_SNAPSHOT_ENABLED`（默认 true，控制后台刷新）、`MEDIA_SERVER_SNAPSHOT_DIR`、`MEDIA_SERVER_SNAPSHOT_REFRESH_HOURS`（默认 6）、`MEDIA_SERVER_SNAPSHOT_TARGET_SIZE`（默认 60）、`MEDIA_SERVER_CURATION_FILE`（默认分类配置同目录下 `curation.json`）、`MEDIA_SERVER_CATALOG_DB`（默认 `data/catalog.db`，置空则关闭 catalog）。
- **点播解析**：通过本仓库内的内部 `vod_extractor` 服务解析公开视频点播地址，本服务不内置 yt-dlp。
- **部署**：`workwork` 以 git submodule 引用本仓库，docker-compose 从 `services/media_center/media_server` 构建。

## UI/UX 设计约定

- **固定交互控件尺寸**：按钮、chip、圆形 icon button 等可聚焦/可点击控件使用 `requiredHeight` / `requiredSize` 保证可操作面积，不用普通 `height` / `size` 让父布局压缩。
- **长文本不可挤压操作区**：标题、简介、错误文案、分类名、按钮文案必须设置 `maxLines` 和 `TextOverflow.Ellipsis`。详情页这类“文案 + 主操作”布局中，主操作区优先保留空间；长简介先折叠，超出后用“查看详情”弹层展示完整内容，不能在原布局内无限展开。
- **详情展开弹层**：详情页简介超出折叠行数时使用紧凑“查看完整简介”披露按钮；完整简介在独立 Dialog 阅读卡片中展示，保留 BACK 关闭路径和主按钮焦点，避免在详情页原布局内撑开。
- **图标风格统一**：播放、暂停、搜索、清除、箭头等图标使用 Canvas/vector/组件绘制，不把 `▶`、`⏸`、emoji 等 Unicode 字符当图标拼进 `Text`，避免 Android 字体 fallback 导致风格不一致。
- **TV 焦点态一致**：焦点态统一使用 Apple Blue 背景、白色文字/图标、`GlassBorderFocused` 近白外框；selected 只表达当前分类/选项，不应强过焦点态。焦点切换应使用明确的 `FocusRequester` / `focusProperties` 路径，避免依赖空间猜测。
- **Back 行为稳定**：详情页、播放页、搜索面板等屏幕级返回使用 `BackHandler`，不要依赖子组件焦点或 `onPreviewKeyEvent` 捕获 Back。
- **Apple TV 视觉基线**：OLED 深色背景、玻璃灰卡片、Apple Blue 作为唯一强调色；文字层级保持 Primary/Secondary/Tertiary 灰阶，不新增不一致的强调色。

## 客户端缓存约定

- **详情缓存上限**：App 可在本地 `cacheDir/video-details` 缓存视频详情元数据，单条缓存 24 小时内有效，最大空间 50MB，按最近访问时间淘汰。
- **签名 URL 不落盘**：服务端文档明确 `thumbnailUrl` 和 `playbackOptions[].url` 是签名 `/asset?url=...&sig=...` 代理地址。客户端缓存不得持久化这些 URL、sig、token 或原始资源地址。
- **可缓存字段**：只缓存稳定字段：`id`、`videoKind`、`title`、`description`、`year`、`creators`、`durationSeconds`、`playbackKind`，以及播放选项的 `id`、`kind`、`label`、`mimeType`。缓存还原出的 `PlaybackOption.url` 只能作为空占位。
- **播放前刷新**：任何真正进入播放器的路径都必须调用 `/videos/{id}/playback` 获取新鲜播放 URL；不能直接使用详情缓存中的占位 URL。
- **缩略图来源**：详情缓存不保存 `thumbnailUrl`。详情页可复用当前列表项内存里的缩略图 URL；若缺失则展示占位图。

## API 约定

以本仓库 `media_server/API.md` 为准。App 侧只消费服务端 URL 下的分类、搜索、详情、播放地址和 asset 代理接口；公开 API 不暴露具体视频来源，客户端无需感知内容来自哪个 Provider。

## 约束

- **视频类型**：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- **播放代理**：播放地址由服务端返回，支持边下边播和 seek。
- **合规**：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- **服务端变更**：`media_server`（Provider、分类、缓存、搜索、播放地址解析）在本仓库 `media_server/` 维护；yt-dlp 点播解析服务在本仓库 `vod_extractor/` 维护。
