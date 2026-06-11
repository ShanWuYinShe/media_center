# CLAUDE.md

内部点播解析服务：Python stdlib HTTP server + yt-dlp。只在 Docker `internal_docker` 网络中供 `media_server` 调用。

> 仓库级 workflow 与 Git 规范以上层 `media_center/CLAUDE.md`、部署方 `workwork/CLAUDE.md` 和全局 `~/.claude/CLAUDE.md` 为准；本文件只补充本服务约束。

## 约束

- 不暴露公网端口。
- 运行 yt-dlp 时必须使用参数数组，不使用 shell。
- 只接受 HTTP/HTTPS URL。
- 响应体和请求体都需要限制大小，避免解析器输出失控。
