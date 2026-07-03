# Media Center Media Server

`media_center` 的合规公开视频聚合后端：Go HTTP 服务，对外只提供目录服务。

API 契约见 @API.md。

## 构建 & 测试

```bash
go build ./...        # 编译
go vet ./...          # 静态检查
gofmt -l .            # 格式检查（列出不合规文件）
go test ./...         # 单元测试
```

> HTTP 服务监听 `PORT`（容器内默认 8787）。

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
