# Media Server

Go 实现的纯目录服务。不调任何上游 API、不做播放地址解析、不做 asset 代理。播放完全由 App 端按 provider 自行处理。

## 当前架构

- 目录服务从已审核（`approved`）的 catalog（SQLite）提供 provider 列表、分类列表、视频元数据。
- `GET /api/providers`：有哪些视频源。
- `GET /api/providers/{provider}/categories`：某视频源有哪些分类。
- `GET /api/providers/{provider}/categories/{categoryId}/videos`：某分类下已审核的视频列表。

## API

完整 App 对接说明见 [`API.md`](./API.md)。

- `GET /api/health`
- `GET /api/providers`
- `GET /api/providers/{provider}/categories`
- `GET /api/providers/{provider}/categories/{categoryId}/videos`
- `GET /api/app/update?versionCode=N`
- `GET /api/app/download`

## 配置与部署

- `CONTENT_CATEGORIES_FILE`：运行时分类配置。workwork admin 会生成并挂载到 `/config/contentCategories.json`。
- `MEDIA_SERVER_CATALOG_DB`：SQLite 数据库路径，默认 `data/catalog.db`。设为空则关闭 catalog。
- `MEDIA_SERVER_PUBLIC_URL`：可选，对外可见 API 基址（如 `https://yinghuoren.com/api/media`），用于拼接 app download URL。
- `HOST` / `PORT`：监听地址，默认 `0.0.0.0:8787`。
- `MEDIA_SERVER_INTERNAL_TOKEN`：内部管理接口令牌，用于 `/internal/catalog/*` 管理接口认证。

## 数据模型

分类浏览与搜索只返回 `approved` 数据，**不做实时上游搜索/采集**。候选经 admin `/internal/catalog/import` 导入 → 人工 + AI 审核 → 上线 `approved`。`VideoSummary` 只有五个字段：`id`（nativeId）、`title`、`description`、`thumbnailUrl`、`durationSeconds`。
