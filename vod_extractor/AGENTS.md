# Media Center VOD Extractor

 本目录（`vod_extractor/`）是 `media_center` 仓库（`ShanWuYinShe/media_center`）的普通子目录，非独立仓库。**本仓库已不再被 `workwork` 以 git submodule 引用**——workwork 已无 `services/media_center` 检出；部署编排在本仓库 `deploy/` 内自洽。

内部点播解析服务：Python stdlib HTTP server + yt-dlp。设计上在 Docker `internal_docker` 网络中供其它服务内网调用（不暴露公网端口）。

**当前状态：已启用。** `media_server` 的 youtube 实时通道调用本服务：`POST /search`（yt-dlp `ytsearch` 实时搜索，无 Data API 配额）与 `POST /extract`（播放直链解析）；`GET /health` 供 compose 健康检查。调用方经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 寻址（compose 内 `http://vod_extractor:8790`）。

## 部署硬前提（服务器）

YouTube 对数据中心 IP 的所有 player_client 都触发 "Sign in to confirm you're not a bot"：服务器部署必须放置 YouTube cookies（Netscape 格式）到 `.wework/vod_extractor/youtube-cookies.txt`（容器只读挂载为 `/config/youtube-cookies.txt`），否则解析与搜索一律失败。优先通过 `VOD_EXTRACTOR_EXTRACTOR_ARGS`（如 `youtube:player_client=tv`）与 cookies 双管齐下；cookies 过期后需重新导出。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
