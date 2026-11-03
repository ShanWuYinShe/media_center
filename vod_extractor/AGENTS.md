# Media Center VOD Extractor

 本目录（`vod_extractor/`）是 `media_center` 仓库的普通子目录，非独立仓库；部署编排在本仓库 `deploy/` 内自洽。与 workwork 的部署关系见根 [AGENTS.md](../AGENTS.md)。

内部点播解析服务：Python stdlib HTTP server + yt-dlp。设计上在 Docker `internal_docker` 网络中供其它服务内网调用（不暴露公网端口）。

`media_server` 的 youtube 实时通道调用本服务：`POST /search`（yt-dlp `ytsearch` 实时搜索，无 Data API 配额）与 `POST /extract`（播放直链解析）；`GET /health` 供 compose 健康检查。调用方经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 寻址（compose 内 `http://vod_extractor:8790`）。

## 部署硬前提（服务器）

YouTube 对数据中心 IP 的**播放解析**触发 "Sign in to confirm you're not a bot"。两个端点的实际影响不同（复核：分别调用 `POST /search` 与 `POST /extract`）：

- **解析（`POST /extract`）**：必须放置 YouTube cookies（Netscape 格式）到 `deploy/data/vod/youtube-cookies.txt`（容器只读挂载为 `/config/youtube-cookies.txt`），否则一律 502 `bot_check`。
- **解析依赖链**：除 cookies 外还需要 ① Deno + `yt-dlp-ejs` 求解签名/n challenge（镜像已内置）；② `bgutil-ytdlp-pot-provider` + 独立 `bgutil_pot` 容器提供 PO Token（compose 已内置，`VOD_EXTRACTOR_EXTRACTOR_ARGS` 必须含 `youtubepot-bgutilhttp:base_url=http://bgutil_pot:4416` 且该键必须排在 `youtube:` 之前（顺序反了插件会退回 127.0.0.1，格式列表只剩 360P））；③ 768M 内存（256M 会被 Deno 求解 OOM kill）。三者缺一都会出现“无格式/ page needs to be reloaded”。cookies 只读挂载，extractor 每次执行复制到容器内可写临时文件供 yt-dlp 回写。
- **搜索（`POST /search`）**：走 yt-dlp 的 `ytsearch` flat 列表页，**不触发该风控**，无 cookies 时仍可用（复核：无 cookies 时 `POST /search` 返回 200；`GET /health` 的 `failures.counts` 中 `bot_check` 不增长）。注意这只代表搜索可用，不代表解析可用。

优先通过 `VOD_EXTRACTOR_EXTRACTOR_ARGS`（如 `youtube:player_client=tv`）与 cookies 双管齐下；cookies 过期后需重新导出。`GET /health` 返回 `cookies` 字段（present/readable/age_seconds）供外部监控告警，`failures.counts` 按分类累计——cookies 缺失时 `bot_check` 会持续增长（分类匹配已兼容 yt-dlp 输出的 Unicode 撇号 U+2019）。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
