# CLAUDE.md

`media_center` 的合规公开视频聚合后端：Go HTTP 服务，对外只提供目录服务——视频来源列表、分类列表、视频元数据。

## 构建 & 测试

```bash
go build ./...        # 编译
go vet ./...          # 静态检查
gofmt -l .            # 格式检查（列出不合规文件）
go test ./...         # 单元测试
```

> HTTP 服务监听 `PORT`（容器内默认 8787）。对外路由统一在 `/api/` 下：`/api/providers`、`/api/providers/{provider}/categories`、`/api/providers/{provider}/categories/{catId}/videos`、`/api/app/update`、`/api/app/download`。

## 约束

- **API 契约**：以本目录 `API.md` 为准。server 端只做目录服务，不做任何上游 API 调用、不做播放地址解析、不做 asset 代理。播放完全由 App 端按 provider 自行处理。
- **内容分类**：默认读取 `src/contentCategories.json`，可用 `CONTENT_CATEGORIES_FILE` 覆盖；线上由 `workwork` 的 admin 注入运行时分类配置。
- **分类数据**：`/api/providers/{provider}/categories/{catId}/videos` 只返回 catalog 中 `approved` 的数据。候选经 admin `/internal/catalog/import` 导入 → 人工 + AI 审核 → 上线 `approved`。服务端绝不做实时上游搜索/采集。
- **视频 id**：`VideoSummary.id` 是 catalog 的 nativeId（如 BV1xxx），App 直接用它调用本地解析器。id 不加密不签名。
- **合规**：只处理公开视频和授权内容。

## 架构

- `main.go`：路由、handler、分页
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
| GET | `/api/health` | 健康检查 |
| GET | `/api/providers` | 有哪些视频源 |
| GET | `/api/providers/{provider}/categories` | 某源的分类 |
| GET | `/api/providers/{provider}/categories/{catId}/videos` | 某分类视频列表(分页) |
| GET | `/api/app/update?versionCode=N` | App 更新检查 |
| GET | `/api/app/download` | App APK 下载 |
| POST | `/internal/catalog/*` | 管理接口(需 X-Internal-Token) |
