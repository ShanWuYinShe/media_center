# Media Center VOD Extractor

 本目录（`vod_extractor/`）是 `media_center` 仓库的普通子目录，非独立仓库；部署编排在本仓库 `deploy/` 内自洽。与 workwork 的脱钩背景见根 [AGENTS.md](../AGENTS.md)。

内部点播解析服务：Python stdlib HTTP server + yt-dlp。设计上在 Docker `internal_docker` 网络中供其它服务内网调用（不暴露公网端口）。

**当前状态：已启用。** `media_server` 的 youtube 实时通道调用本服务：`POST /search`（yt-dlp `ytsearch` 实时搜索，无 Data API 配额）与 `POST /extract`（播放直链解析）；`GET /health` 供 compose 健康检查。调用方经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 寻址（compose 内 `http://vod_extractor:8790`）。

## 部署硬前提（服务器）

YouTube 对数据中心 IP 的**播放解析**触发 "Sign in to confirm you're not a bot"。两个端点的实际影响不同（2026-10-12 线上实测）：

- **解析（`POST /extract`）**：必须放置 YouTube cookies（Netscape 格式）到 `deploy/data/vod/youtube-cookies.txt`（容器只读挂载为 `/config/youtube-cookies.txt`），否则一律 502 `bot_check`。
- **搜索（`POST /search`）**：走 yt-dlp 的 `ytsearch` flat 列表页，**不触发该风控**，无 cookies 时仍可用（实测 cookies 缺失下连续 3 次搜索均 200 / 20 条，失败计数零增长）。注意这只代表搜索可用，不代表解析可用。

优先通过 `VOD_EXTRACTOR_EXTRACTOR_ARGS`（如 `youtube:player_client=tv`）与 cookies 双管齐下；cookies 过期后需重新导出。`GET /health` 返回 `cookies` 字段（present/readable/age_seconds）供外部监控告警，`failures.counts` 按分类累计——cookies 缺失时 `bot_check` 会持续增长（分类匹配已兼容 yt-dlp 输出的 Unicode 撇号 U+2019）。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
