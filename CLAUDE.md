# CLAUDE.md

合规公开视频媒体中心：Android TV 客户端（`media_app`）+ Go 聚合后端（`media_server`）。通用约束见全局 `~/.claude/CLAUDE.md`。

> **开发隔离**：遵循全局 Worktree 约束——开发改动在 `.claude/worktrees/<分支名>`（已被 `.gitignore` 忽略）中进行，主工作树保持干净，只用于同步、只读分析和最终合并，不在主目录直接堆改动。

## 仓库结构

- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端
- `media_server/` — Go 实现的合规公开视频聚合后端（分类、搜索、详情、播放地址、asset 代理）

App 只配置 `MEDIA_SERVER_URL` 一个服务端地址，不关心服务端部署方式。默认服务端 URL 是 `https://yinghuoren.com/api/media`。代理 / 部署基础设施在 `workwork` 仓库，通过 git submodule 引用本仓库来构建 `media_server`；yt-dlp / vod_extractor 点播解析服务仍在 `workwork`。

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
- **点播解析**：通过内部 `vod_extractor`（在 `workwork`）解析公开视频点播地址，本服务不内置 yt-dlp。
- **部署**：`workwork` 以 git submodule 引用本仓库，docker-compose 从 `media_center/media_server` 构建。

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
- **服务端变更**：`media_server`（Provider、分类、缓存、搜索、播放地址解析）在本仓库 `media_server/` 维护；yt-dlp / vod_extractor 点播解析服务仍在 `workwork` 仓库维护。
