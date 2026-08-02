# Media Center VOD Extractor

 本目录是 `media_center` 仓库的 git submodule。修改本文件后，需同步更新 `media_center` 仓库 `vod_extractor/` 下的同名文件。

内部点播解析服务：Python stdlib HTTP server + yt-dlp。只在 Docker `internal_docker` 网络中供 `media_server` 调用。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
