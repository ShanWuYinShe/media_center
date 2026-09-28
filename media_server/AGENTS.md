# Media Center Media Server

 本目录（`media_server/`）是 `media_center` 仓库（`ShanWuYinShe/media_center`）的普通子目录，非独立仓库。**本仓库已不再被 `workwork` 以 git submodule 引用**——workwork 已无 `services/media_center` 检出（media/music 已拆分为独立站仓库）；部署编排在本仓库 `deploy/` 内自洽（`media.chaoset.com`）。

`media_center` 的合规公开视频聚合后端：Go HTTP 服务，以目录服务为主（catalog SQLite 为 system of record）；`youtube` provider 的搜索/解析走受控实时通道（见 `youtube.go`）。

API 契约见 `API.md`。

## 构建 & 测试

```bash
go build ./...        # 编译
go vet ./...          # 静态检查
gofmt -l .            # 格式检查（列出不合规文件）
go test ./...         # 单元测试
```

HTTP 服务监听 `PORT`（容器内默认 8787）。

## catalog 与内容审核（详见 skill media-server-catalog）

分类数据模型、导入与审核流程、youtube 实时搜索受控例外已移入 skill media-server-catalog。

## 架构

- `main.go`：路由、handler、分页
- `youtube.go`：youtube provider 实时通道——搜索与播放解析均代理 vod_extractor/yt-dlp（`MEDIA_SERVER_VOD_EXTRACTOR_URL`；搜索走 ytsearch flat 模式，无 Data API 配额），均需 `X-App-Token` + 独立限流
- `catalog.go`：SQLite 分类维护数据库（system of record），纯 modernc.org/sqlite 无 cgo
- `catalog_serve.go`：CatalogVideo → VideoSummary 转换
- `config.go`：环境变量配置加载
- `internal_api.go`：`/internal/*` 管理接口（catalog import/export/review/delete），仅限内网
- `review.go`：交互式审校命令（`-catalog-review`）
- `app_update.go`：Android TV App 更新发布检查与 APK 下载
- `types.go`：类型定义（VideoSummary、ProviderInfo、PublicCategory 等）

## 路由

对外规范前缀是 `/api/v1`（独立站约定，`main.go` 入口把 `/api/v1/x` 归一为 `/api/x`）；
下表按内部路由书写，旧 `/api/*` 为过渡期废弃别名，行为与 `/api/v1/*` 完全一致。

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/health` | 健康检查(容器 healthcheck 与内网探活用) |
| GET | `/healthz` | 存活探针(独立站约定,无鉴权,nginx /healthz 直达) |
| GET | `/api/health` | 健康检查公网别名(经 nginx 前缀重写,排障可直接 curl) |
| GET | `/api/providers` | 有哪些视频源 |
| GET | `/api/providers/{provider}/categories` | 某源的分类 |
| GET | `/api/providers/{provider}/categories/{catId}/videos` | 某分类视频列表(分页) |
| GET | `/api/search?q=&provider=` | approved 目录关键词搜索(分页,限流 30/min/IP,并发满载 503) |
| GET | `/api/providers/credentials` | Provider 凭证下发(需 X-App-Token,限流 30/min/IP) |
| GET | `/api/providers/youtube/search?q=` | YouTube 实时搜索(需 X-App-Token,限流 8/min/IP) |
| GET | `/api/providers/youtube/resolve?id=` | YouTube 播放解析(需 X-App-Token,限流 20/min/IP) |
| GET | `/api/app/update?versionCode=N&variant=` | App 更新检查(variant: normal/legacy) |
| GET | `/api/app/download?variant=` | App APK 下载 |
| POST | `/api/crash/report` | App 崩溃上报(限流 10/min/IP) |
| POST | `/internal/catalog/*` | 管理接口(需 X-Internal-Token;独立站部署不对公网暴露,经宿主机调用) |
