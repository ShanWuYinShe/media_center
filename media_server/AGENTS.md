# Media Center Media Server

 本目录（`media_server/`）是 `media_center` 仓库（`ShanWuYinShe/media_center`）的普通子目录，非独立仓库；该仓库被 `workwork` 以 git submodule 引用（`services/media_center`）。

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

## catalog 与内容审核

- 内容分类：`src/contentCategories.json`；线上由 `workwork` 的 admin 生成运行时分类配置注入。
- 分类数据（catalog，SQLite，system-of-record）：分类视频列表由 `catalog.go` 用纯 Go 的 `modernc.org/sqlite`（无需 cgo）落地维护库；分类浏览只读该库的 `approved` 数据（见 `catalog_serve.go` 与 `catalog.go` 的 `SearchApproved`）；没有 `approved` 数据时返回空列表 / 空搜索结果。
  - 候选不提交仓库：候选（外部 / AI 收集汇总）是中间状态，不纳入版本管理；最终数据以 catalog.db（system-of-record）为准，可通过 `/internal/catalog/export` 导出。各分类的收集格式与审核标准见 `CATEGORY_CURATION_GUIDE.md`。
  - 流程：外部收集候选 → 导入 pending → 人工 + AI 审核 → 上线 approved。候选经 `/internal/catalog/import` 导入为 `pending`（重复导入只新增，绝不覆盖已有审核决定）；在 admin 页面由人工 + AI 模型审核 approve / reject，可选 `pinned` / `rank`（经 `/internal/catalog/*`，也可用 `-catalog-review` 交互命令或任意 SQLite 工具）；客户端的分类浏览与搜索只命中该库 `approved` 清单（顺序 `pinned` > `rank` > `score`）。
  - 审核是唯一的质量关口：聚合视频未经审核普遍标题与内容不符、大量无法播放，相关性、可播放性等筛选一律交给 admin 的人工 + AI 审核。**youtube 实时搜索是这个原则的受控例外**：`/api/providers/youtube/search` 经 vod_extractor 用 yt-dlp ytsearch 实时搜索（无 Data API 配额），只为 App 即时展示/播放，结果不写 catalog、不进分类列表，审核关卡不被绕过。
- 只存原始数据：catalog 只存上游原始字段(缩略图为原始地址)。
- 数据库文件不提交:catalog 库写到 `MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,含 `-wal`/`-shm` 旁文件,已 gitignore);线上由 `workwork` 挂卷生成。
  - 相关环境变量:`MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,置空则关闭 catalog,分类浏览与搜索返回空)。

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

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/health` | 健康检查(容器 healthcheck 与内网探活用) |
| GET | `/api/health` | 健康检查公网别名(经 nginx 前缀重写,排障可直接 curl) |
| GET | `/api/providers` | 有哪些视频源 |
| GET | `/api/providers/{provider}/categories` | 某源的分类 |
| GET | `/api/providers/{provider}/categories/{catId}/videos` | 某分类视频列表(分页) |
| GET | `/api/search?q=&provider=` | approved 目录关键词搜索(分页) |
| GET | `/api/providers/credentials` | Provider 凭证下发(需 X-App-Token,限流 30/min/IP) |
| GET | `/api/providers/youtube/search?q=` | YouTube 实时搜索(需 X-App-Token,限流 8/min/IP) |
| GET | `/api/providers/youtube/resolve?id=` | YouTube 播放解析(需 X-App-Token,限流 20/min/IP) |
| GET | `/api/app/update?versionCode=N&variant=` | App 更新检查(variant: normal/legacy) |
| GET | `/api/app/download?variant=` | App APK 下载 |
| POST | `/api/crash/report` | App 崩溃上报(限流 10/min/IP) |
| POST | `/internal/catalog/*` | 管理接口(需 X-Internal-Token) |
