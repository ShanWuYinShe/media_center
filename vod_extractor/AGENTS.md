# Media Center VOD Extractor

 本目录（`vod_extractor/`）是 `media_center` 仓库（`ShanWuYinShe/media_center`）的普通子目录，非独立仓库；该仓库被 `workwork` 以 git submodule 引用（`services/media_center`）。

内部点播解析服务：Python stdlib HTTP server + yt-dlp。设计上在 Docker `internal_docker` 网络中供其它服务内网调用（不暴露公网端口）。

**当前状态：未启用。** `media_server` 是纯目录服务、当前无任何代码调用 `vod_extractor`；该服务为 YouTube 等外站点播解析预留，因国内网络访问限制暂不启用，待有代理基础设施后再接入。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
