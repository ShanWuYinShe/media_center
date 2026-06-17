# CLAUDE.md

`media_center` 的合规公开视频聚合后端：Go HTTP 服务，对外提供分类、搜索、详情、播放地址与 asset 代理接口（当前 Provider：Bilibili）。

## 构建 & 测试

```bash
go build ./...        # 编译
go vet ./...          # 静态检查
gofmt -l .            # 格式检查（列出不合规文件）
go test ./...         # 单元测试
```

> HTTP 服务监听 `PORT`（容器内默认 8787）。对外路由统一在 `/api/` 下：`/api/categories`、`/api/categories/{id}/videos`、`/api/search`、`/api/videos/{id}`、`/api/videos/{id}/playback`、`/api/asset`。

## 约束

- **API 契约**：以本目录 `API.md` 为准。`VideoSummary/VideoDetails` **会**透传 `provider + nativeId`(catalog 原始来源),让 App 端按 provider 自行决定走本地解析(国内可直连源)还是 server 解析(国外源);`PlaybackOption` 本身不带任何"是否本地解析"标记。
- **视频类型**：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- **asset 代理**：`/api/asset` 为签名代理（由 `MEDIA_SERVER_SIGNING_KEY` 签名），支持 Range 边下边播和 seek；签名地址不可被客户端持久化。
- **内容分类**：默认读取 `src/contentCategories.json`，可用 `CONTENT_CATEGORIES_FILE` 覆盖；线上由 `workwork` 的 admin 注入运行时分类配置。
- **分类与搜索数据**：`/api/categories/{id}/videos` 和 `/api/search` 都只返回 catalog 中 `approved` 的数据，服务端**不做实时上游搜索/采集**；候选经 admin `/internal/catalog/import` 导入 → 人工 + AI 审核 → 上线 `approved`（详见本仓库根 `CLAUDE.md` 与 `CATEGORY_CURATION_GUIDE.md`）。聚合视频未经审核普遍标题与内容不符、大量无法播放，故不提供任何实时/未审核数据。
- **Provider 解析路径分流**（关键）：
  - **国内可直连源(当前 bilibili)**：server 端**不再**调上游 API。`handleVideos` 对这类 provider 走 catalog 路径(`detailsFromCatalog`)返回元数据 + 空 `playbackOptions`;`handlePlayback` 直接返回空数组。App 端拿到 `VideoSummary.provider=bilibili` 后自己调 B 站 API(view + playurl + WBI 签名)拿真实直链。`isClientResolvedProvider(providerID)` 判断哪些 provider 走此路径。bilibili.go 的 `GetDetails/GetPlaybackOptions` 只是 catalog 未命中时的兜底,不会真正调上游。
  - **国外源(youtube 等)**：server 端调上游 API/解析服务。youtube 通过 `vod_extractor`(yt-dlp)解析,直链走 `/asset` 签名代理(proxy delivery)。
- **合规**：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- **新增 Provider**：国内可直连的新源在 `isClientResolvedProvider` + App 端 `MediaRepository.playbackOptions` 的 provider 分流里加分支即可(server 端零代码,App 端实现本地解析器);国外源沿用 youtube 的 server 解析 + vod_extractor 模式。
- **点播解析**：本服务不内置 yt-dlp；国外源(youtube)由同仓库 `vod_extractor/` 服务负责解析(Docker 内网调用)。国内可直连源(bilibili)由 App 端自己解析,不走 vod_extractor。
