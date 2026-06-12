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

- **API 契约**：以本目录 `API.md` 为准；公开 API 不暴露具体视频来源。
- **视频类型**：`vod`（点播）是当前唯一支持类型；`live`（直播）不处理。
- **asset 代理**：`/api/asset` 为签名代理（由 `MEDIA_SERVER_SIGNING_KEY` 签名），支持 Range 边下边播和 seek；签名地址不可被客户端持久化。
- **内容分类**：默认读取 `src/contentCategories.json`，可用 `CONTENT_CATEGORIES_FILE` 覆盖；线上由 `workwork` 的 admin 注入运行时分类配置。
- **合规**：只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。
- **新增 Provider**：复用现有 Bilibili 客户端的模式扩展，使用官方 API 或合规开源工具。
- **点播解析**：本服务不内置 yt-dlp；需外部解析的来源由 `workwork` 的内部 `vod_extractor` 服务负责（集成方式见 `workwork/CLAUDE.md`）。
