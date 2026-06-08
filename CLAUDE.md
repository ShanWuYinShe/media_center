# CLAUDE.md

合规公开视频媒体服务：TypeScript 服务端聚合 Bilibili/YouTube，供 Android TV 客户端播放。

## 命令

```bash
npm --prefix services/media_server run dev
npm --prefix services/media_server run build
npm --prefix services/media_server run lint
npm --prefix services/media_server test
```

## 约束

- **视频类型**：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- **播放代理**：通过 `/api/asset` 做 Range 代理，支持边下边播和 seek。
- **合规**：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- **新来源**：通过继承 `VideoProvider` 接口扩展，使用官方 API 或合规开源工具。
- **缓存**：后端按 URL + Range 缓存点播片段，默认目录 `/cache`，上限 50GB。
- **yt-dlp**：线上通过内部 `vod_extractor` 服务解析 YouTube 点播，`media_server` 使用 `VOD_EXTRACTOR_URL` 调用；本地开发无 extractor 服务时可用 `VOD_EXTRACTOR_BIN` 指向本机 `yt-dlp`。
