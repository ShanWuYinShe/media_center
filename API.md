# Media Server API

本文档描述 Android TV App 需要对接的公开 API。App 不需要知道内容来自 Bilibili、YouTube 或其他 Provider，也不要直接访问第三方媒体 URL；所有图片和点播地址都会被服务端改写为带签名的 `/asset` 代理地址。

机器可读版本见 [`openapi.json`](./openapi.json)。

## Base URL

App 应把配置值当作 **API base URL**，也就是下面端点表中路径的前缀。当前默认服务端 URL 是 `https://yinghuoren.com/api/media`。

| 场景 | API base URL 示例 |
|------|-------------------|
| 默认线上服务 | `https://yinghuoren.com/api/media` |

默认公开前缀是 `/api/media`，可在 workwork 管理台 Media Center 页面调整。App 不需要也不应该知道内部 upstream。

## 通用约定

- 编码：所有 JSON 请求/响应均使用 UTF-8。
- 认证：Media Server 公开 API 当前不要求 App 认证；线上访问控制由外层 nginx/Cloudflare 策略决定。
- 视频类型：当前只公开 `vod` 点播；`live` 不搜索、不展示、不播放。
- 视频 ID：`id` 是服务端生成的不透明字符串，App 必须原样保存，并在放入 URL path 时执行 URL encode。
- 分页：`page` 从 `1` 开始；`pageSize` 范围是 `1..40`，默认 `20`。
- 图片和播放 URL：`thumbnailUrl`、`playbackOptions[].url` 都是服务端签发的 `/asset?url=...&sig=...` 代理地址，App 应直接使用它们，不要自行拼接。
- Range：播放代理支持 HTTP Range。Media3/ExoPlayer 正常发起 Range 请求即可。

## 错误响应

```json
{
  "error": {
    "code": "BAD_REQUEST",
    "message": "请求参数不合法。"
  }
}
```

| HTTP 状态 | code | 含义 |
|-----------|------|------|
| 400 | `BAD_REQUEST` | 参数格式或范围不合法 |
| 404 | `NOT_FOUND` | 分类、视频或 Provider 不存在 |
| 422 | `NO_PLAYABLE_STREAM` | 视频不是可播放点播，或没有可播放流 |
| 500 | `INTERNAL_ERROR` | 服务端内部错误 |
| 502 | `PROVIDER_TIMEOUT` | 上游 Provider 或解析过程超时 |
| 502 | `PROVIDER_UNAVAILABLE` | 上游 Provider、点播解析器或媒体地址不可用 |

## 数据模型

### Category

```ts
type Category = {
  id: string;
  title: string;
  description: string;
};
```

### VideoSummary

```ts
type VideoSummary = {
  id: string;
  videoKind: "vod";
  title: string;
  description: string;
  year?: string;
  creators: string[];
  thumbnailUrl?: string;
  durationSeconds?: number;
  playbackKind: "vod";
};
```

### VideoDetails

```ts
type VideoDetails = VideoSummary & {
  playbackOptions: PlaybackOption[];
};
```

### PlaybackOption

```ts
type PlaybackOption = {
  id: string;
  kind: "vod";
  label: string;
  url: string;
  mimeType: "video/mp4" | "video/webm";
};
```

### PagedVideos

```ts
type PagedVideos = {
  query: string;
  category?: Category;
  page: number;
  pageSize: number;
  total: number;
  items: VideoSummary[];
};
```

`total` 表示本次服务端已整理出的可播放结果数量，不等同于第三方平台总量。

## Endpoints

### `GET /categories`

获取首页一级分类。

响应：

```json
{
  "items": [
    {
      "id": "movies",
      "title": "电影",
      "description": "院线电影、国产经典、海外大片和完整长片。"
    }
  ]
}
```

### `GET /categories/{id}/videos`

获取某个分类下的可播放视频列表。分类列表由服务端定期缓存；当 App 请求超过当前缓存深度时，服务端会按需扩展。

Query：

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `page` | integer | `1` | 从 1 开始 |
| `pageSize` | integer | `20` | 1 到 40 |

响应：

```json
{
  "query": "",
  "category": {
    "id": "movies",
    "title": "电影",
    "description": "院线电影、国产经典、海外大片和完整长片。"
  },
  "page": 1,
  "pageSize": 20,
  "total": 20,
  "items": [
    {
      "id": "bilibili:QlZ4eHh4",
      "videoKind": "vod",
      "title": "示例电影",
      "description": "视频简介",
      "year": "2024",
      "creators": ["示例频道"],
      "thumbnailUrl": "https://yinghuoren.com/api/media/asset?url=...&sig=...",
      "durationSeconds": 7200,
      "playbackKind": "vod"
    }
  ]
}
```

### `GET /search`

临时关键词搜索。搜索响应不缓存；服务端会先验证点播播放地址，只返回可播放的 `vod`。

Query：

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `q` | string | 必填 | 1 到 120 个字符 |
| `page` | integer | `1` | 从 1 开始 |
| `pageSize` | integer | `20` | 1 到 40 |

响应结构同 `PagedVideos`，但通常没有 `category` 字段。

### `GET /videos/{id}`

获取视频详情和可播放选项。`id` 来自列表或搜索结果，App 必须 URL encode。

响应：

```json
{
  "id": "youtube:ZGV0YWlsaWQ",
  "videoKind": "vod",
  "title": "示例视频",
  "description": "视频简介",
  "creators": ["示例频道"],
  "thumbnailUrl": "https://yinghuoren.com/api/media/asset?url=...&sig=...",
  "durationSeconds": 630,
  "playbackKind": "vod",
  "playbackOptions": [
    {
      "id": "youtube-detailid-18",
      "kind": "vod",
      "label": "360p",
      "url": "https://yinghuoren.com/api/media/asset?url=...&sig=...",
      "mimeType": "video/mp4"
    }
  ]
}
```

### `GET /videos/{id}/playback`

只获取播放选项。适合列表页已有足够元数据、进入播放器前单独刷新播放地址。

响应：

```json
{
  "items": [
    {
      "id": "youtube-detailid-18",
      "kind": "vod",
      "label": "360p",
      "url": "https://yinghuoren.com/api/media/asset?url=...&sig=...",
      "mimeType": "video/mp4"
    }
  ]
}
```

### `GET /asset?url={encodedUrl}&sig={signature}`

媒体和图片代理端点。App 通常不需要手工拼接这个端点，只需要使用 API 返回的 `thumbnailUrl` 和 `playbackOptions[].url`。

请求：

- `url`：必填，原始 HTTP/HTTPS 资源 URL，必须 URL encode。
- `sig`：必填，服务端签发的 HMAC 签名。App 不需要理解签名算法，只需原样使用 API 返回的完整 URL。
- `Range`：可选；播放器发送 Range 时服务端会透传给上游。

响应：

- 状态码通常是 `200` 或 `206`，取决于上游和 Range 请求。
- 关键响应头：`Content-Type`、`Content-Length`、`Content-Range`、`Accept-Ranges`、`X-Cache`。
- `X-Cache` 可能是 `HIT`、`MISS` 或 `BYPASS`。

### `GET /health`

服务健康检查。公开服务端 URL 下可直接请求 `/health`。

响应：

```json
{ "ok": true }
```

## App 推荐流程

1. 启动首页：`GET /categories`。
2. 进入分类：`GET /categories/{id}/videos?page=1&pageSize=20`。
3. 搜索页：`GET /search?q=...&page=1&pageSize=20`。
4. 进入详情页：`GET /videos/{id}`。
5. 播放：选择 `playbackOptions[0].url` 交给 Media3/ExoPlayer。
6. 需要更多清晰度时：展示 `playbackOptions`，按 `label` 选择。

## App 兼容性要求

- 未识别字段应忽略，方便服务端后续扩展。
- `playbackKind` 和 `kind` 当前只会是 `vod`，但 App 应按枚举处理。
- `thumbnailUrl`、`year`、`durationSeconds` 可能缺失。
- `playbackOptions` 可能为空或请求返回 `NO_PLAYABLE_STREAM`，App 应展示“暂不可播放”。
- 不要依赖 `id` 的内部格式；它可能包含 `:`、`-`、`_` 等字符。
