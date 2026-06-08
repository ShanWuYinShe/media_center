# CLAUDE.md

合规公开视频媒体中心 Android TV 客户端。通用约束见全局 `~/.claude/CLAUDE.md`。

## 仓库结构

- `media_app/` — Android TV + Kotlin + Jetpack Compose + Media3 客户端

服务端/API 已迁移到 `workwork/services/media_server`。App 只配置 `MEDIA_SERVER_URL` 一个服务端地址，不关心服务端部署方式。默认服务端 URL 是 `https://yinghuoren.com/api/media`。

## media_app

```bash
./gradlew :app:assembleDebug
```

- **技术栈**：Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- **服务端地址**：默认 `https://yinghuoren.com/api/media`；如需覆盖，设置 `MEDIA_SERVER_URL`，可以是服务根地址，也可以直接带 API 路径。

## API 约定

以 `workwork/services/media_server/API.md` 和 `workwork/services/media_server/openapi.json` 为准。App 侧只消费服务端 URL 下的分类、搜索、详情、播放地址和 asset 代理接口；公开 API 不暴露具体视频来源，客户端无需感知内容来自哪个 Provider。

## 约束

- **视频类型**：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- **播放代理**：播放地址由服务端返回，支持边下边播和 seek。
- **合规**：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- **服务端变更**：Provider、分类、缓存、yt-dlp/vod_extractor 等服务端逻辑只在 `workwork` 仓库维护。
