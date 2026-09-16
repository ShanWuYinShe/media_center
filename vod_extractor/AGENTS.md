# Media Center VOD Extractor

 本目录（`vod_extractor/`）是 `media_center` 仓库（`ShanWuYinShe/media_center`）的普通子目录，非独立仓库；该仓库被 `workwork` 以 git submodule 引用（`services/media_center`）。

内部点播解析服务：Python stdlib HTTP server + yt-dlp。设计上在 Docker `internal_docker` 网络中供其它服务内网调用（不暴露公网端口）。

**当前状态：已启用。** `media_server` 的 youtube 实时通道调用本服务：`POST /search`（yt-dlp `ytsearch` 实时搜索，无 Data API 配额）与 `POST /extract`（播放直链解析）；`GET /health` 供 compose 健康检查。调用方经 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 寻址（compose 内 `http://vod_extractor:8790`）。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
