# Media Server API

本文档描述 Android TV App 对接的公开 API。server 端是纯目录服务——只返回视频元数据（id、标题、简介、封面），不提供播放地址解析。播放由 App 端按 provider 自行处理（bilibili → BilibiliApi 本地解析）。

## 当前契约

- server 端只从已审核（`approved`）的 catalog 返回数据。
- `GET /api/providers`：有哪些视频源网站。
- `GET /api/providers/{provider}/categories`：某视频源有哪些分类。
- `GET /api/providers/{provider}/categories/{categoryId}/videos`：某分类下已审核的视频列表。

## 错误响应

```json
{ "error": { "code": "BAD_REQUEST", "message": "请求参数不合法。" } }
```

| HTTP 状态 | code | 含义 |
|-----------|------|------|
| 400 | `BAD_REQUEST` | 参数格式不合法 |
| 404 | `NOT_FOUND` | 分类不存在 |
| 500 | `INTERNAL_ERROR` | 服务端内部错误 |

## 数据模型

```ts
type ProviderInfo = {
  id: string;       // "bilibili"
  name: string;     // "B站"
};
type Category = {
  id: string;
  title: string;
  description: string;
};
type VideoSummary = {
  id: string;            // nativeId（如 BV1xxx），App 直接用它调本地解析器
  title: string;
  description: string;
  thumbnailUrl?: string;
  durationSeconds?: number;
};
```

## Endpoints

### `GET /providers`

返回 catalog 中有 approved 视频的所有视频源。

```json
{ "items": [{ "id": "bilibili", "name": "B站" }] }
```

### `GET /providers/{provider}/categories`

返回某视频源下有 approved 视频的分类列表。

```json
{ "items": [{ "id": "movies", "title": "电影", "description": "院线电影" }] }
```

### `GET /providers/{provider}/categories/{categoryId}/videos?page=1&pageSize=20`

返回某分类下已审核的视频列表（分页，按 pinned > rank > score 排序）。

```json
{
  "category": { "id": "movies", "title": "电影", "description": "院线电影" },
  "page": 1,
  "pageSize": 20,
  "total": 120,
  "items": [
    { "id": "BV1xxx", "title": "示例视频", "description": "简介", "thumbnailUrl": "https://...", "durationSeconds": 3600 }
  ]
}
```

### `GET /app/update?versionCode=1`

返回 Android TV App 的最新发布版本。已是最新返回 `204 No Content`。

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://yinghuoren.com/api/media/app/download",
  "sizeBytes": 12345678,
  "sha256": "64 字符 SHA-256 hex",
  "releaseNotes": "修复播放失败后跳回首页的问题",
  "publishedAt": "2026-06-14T04:00:00Z"
}
```

### `GET /app/download`

下载 admin 发布并审查过的 Android TV App APK。

## 部署相关

- `MEDIA_SERVER_PUBLIC_URL`（可选）：对外可见的 API 基址（如 `https://yinghuoren.com/api/media`）。位于反代后面时必须设置。
- `contentCategories.json` 定义分类 ID 到标题/描述的映射。sources 字段保留解析（向后兼容旧配置）但不再使用。

## App 侧必须遵守

1. `VideoSummary.id` 是 catalog 的 nativeId（如 BV1xxx），不是 server 加密 ID。App 直接用它调用本地解析器。
2. 当前仅 `provider=bilibili` 被支持。App 端实现 `BilibiliApi`（WBI 签名 + view + playurl）拿真实播放直链。
3. 缩略图 URL 是上游原始地址（如 B 站 CDN 链接），App 直连时需带 `Referer: https://www.bilibili.com` 请求头。
