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
- `GET /api/search?q=…`：在已审核目录中按关键词搜索（标题/简介/作者，跨分类去重）。
- `GET /api/providers/credentials`：provider 凭证（如 bilibili SESSDATA），**需 `X-App-Token`**（见下）。
- `GET /api/app/update?versionCode=N`
- `GET /api/app/download`

## 配置与部署

- `CONTENT_CATEGORIES_FILE`：运行时分类配置。workwork admin 会生成并挂载到 `/config/contentCategories.json`。
- `MEDIA_SERVER_CATALOG_DB`：SQLite 数据库路径，默认 `data/catalog.db`。设为空则关闭 catalog。
- `MEDIA_SERVER_PUBLIC_URL`：**建议必填**，对外可见 API 基址（如 `https://yinghuoren.com/api/media`），用于拼接 app download URL。未配置时下载地址由请求 Host 推导，仅接受 `MEDIA_SERVER_ALLOWED_HOSTS` 白名单或本地网段——防止 Host 头投毒把更新包指向攻击者域名。
- `MEDIA_SERVER_ALLOWED_HOSTS`：逗号分隔的公网域名白名单，仅当 `MEDIA_SERVER_PUBLIC_URL` 未配置时用于校验下载 Host。
- `MEDIA_SERVER_APP_API_TOKEN`：凭证接口令牌。为空时 `/api/providers/credentials` 一律 401（App 降级无凭证模式）。App 构建时通过 gradle 属性 `APP_API_TOKEN` 注入同一值。
- `HOST` / `PORT`：监听地址，默认 `0.0.0.0:8787`。
- `MEDIA_SERVER_INTERNAL_TOKEN`：内部管理接口令牌，用于 `/internal/catalog/*` 管理接口认证。

## 数据模型

分类浏览与搜索只返回 `approved` 数据，**不做实时上游搜索/采集**。候选经 admin `/internal/catalog/import` 导入 → 人工 + AI 审核 → 上线 `approved`。`VideoSummary` 只有五个字段：`id`（nativeId）、`title`、`description`、`thumbnailUrl`、`durationSeconds`。
