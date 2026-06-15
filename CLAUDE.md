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
- **分类数据(catalog,SQLite,system-of-record)**:分类视频列表由 `media_server/catalog.go` 用纯 Go 的 `modernc.org/sqlite`(无需 cgo)落地维护库;分类浏览直接分页读取该库的 `approved` 行(见 `catalog_serve.go`),**绝不做实时上游搜索**;没有 `approved` 数据时返回空列表。
  - **种子数据 `seeds/categories/`**(提交进仓库):各分类的**粗略候选列表**(粗略搜索 / AI 收集汇总),每个分类一个 JSON,顶层固定 `{"items":[...]}`,与 `POST /internal/catalog/import` 一致;不要用顶层数组。详见 `seeds/README.md`。
  - **流程:粗略搜索 → 导入 pending → 人工 + AI 审核 → 上线 approved**:候选经 `/internal/catalog/import` 导入为 `pending`(重复导入只新增,绝不覆盖已有审核决定)→ 在 **admin 页面由人工 + AI 模型审核** approve/reject、可选 `pinned`/`rank`(经 `/internal/catalog/*`,也可用 `-catalog-review` 交互命令或任意 SQLite 工具)→ 分类浏览即发该库 `approved` 清单(顺序 `pinned` > `rank` > `score`)。
  - **粗略搜索(seed)**:`go run -C media_server . -catalog-seed`(或 admin 的 seed 端点)按分类抓一批**粗略搜索结果**灌为 `pending`。**代码侧只做粗略搜索 + 合规过滤(成人/色情内容),不做分类相关性等精准筛选**——精准筛选一律交给 admin 的人工 + AI 审核。
  - **只存原始数据**:catalog 只存上游原始字段(缩略图为原始地址),签名 `/asset` 代理 URL 每次请求再生成,绝不写进库。
  - **数据库文件不提交**:catalog 库写到 `MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,含 `-wal`/`-shm` 旁文件,已 gitignore);线上由 `workwork` 挂卷生成。
  - **相关环境变量**:`MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,置空则关闭 catalog,分类浏览返回空)。
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
