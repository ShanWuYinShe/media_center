# Media Server API

本文档描述 Android TV App 对接的公开 API。

## Base URL

App 应把配置值当作 **API base URL**。当前线上默认是：

`https://yinghuoren.com/api/media`

## 当前契约

- 后端负责：多来源分类/搜索/详情/播放选项聚合，并决定每个资源的**下发方式**（`deliveryMode`）。
- **缩略图始终走 `proxy`**：`thumbnailUrl` 是签名 `/asset` 代理地址，字节由**后端回源**并转发；
  上游请求头由后端附加，`thumbnailHeaders` 为空。这样可避免 Android TV 直连上游时被老旧证书链、
  Referer/UA 差异或区域网络策略影响。
- **播放流 `direct`（国内可直连源，如 Bilibili）**：后端直接返回上游 `playbackOptions[].url`，
  并通过 `playbackOptions[].headers` 下发直连所需请求头，由 **App 直连上游**，
  避免后端被大流量媒体播放限流。
- **播放流 `proxy`（国内不可直连的国外源）**：后端返回的是签名 `/asset` 代理地址，字节由**后端回源**并转发；
  上游请求头由后端附加，**不会**下发给 App（对应 `headers` 为空）。
- App 必须把 URL 当成**可执行地址**，不要根据 host 推断来源；通过 `deliveryMode` 区分两类资源。
- `playbackOptions[].headers` 必须原样带到对应请求里（`proxy` 模式下为空）。
- `proxy` 模式下的 `thumbnailUrl` / `playbackOptions[].url` 是带签名和有效期的代理地址，**不要持久化**。
- 视频类型当前只支持 `vod`。
- 视频 `id` 和播放选项 `id` 都是不透明值；App 必须原样保存，并在放入 path 时 URL encode。

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
| 400 | `BAD_REQUEST` | 参数格式不合法 |
| 404 | `NOT_FOUND` | 分类或视频不存在 |
| 422 | `NO_PLAYABLE_STREAM` | 视频暂无可直接播放的点播流 |
| 500 | `INTERNAL_ERROR` | 服务端内部错误 |
| 502 | `UPSTREAM_UNAVAILABLE` | 上游 API 或点播地址不可用 |
| 410 | `BAD_REQUEST` | `/asset` 代理地址已过期 |
| 403 | `BAD_REQUEST` | `/asset` 代理地址签名无效 |

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
type DeliveryMode = "direct" | "proxy";

type VideoSummary = {
  id: string;
  videoKind: "vod";
  title: string;
  description: string;
  year?: string;
  creators: string[];
  thumbnailUrl?: string;
  thumbnailHeaders?: Record<string, string>;
  // 缩略图下发方式；当前始终为 proxy，经 /asset 后端代理。
  thumbnailDelivery?: DeliveryMode;
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
  mimeType: "video/mp4" | "video/webm" | "application/dash+xml";
  headers?: Record<string, string>;
  // 播放流下发方式：direct=App 直连上游(带 headers)；proxy=经 /asset 后端代理(headers 为空)。
  delivery?: DeliveryMode;
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

## Endpoints

### `GET /categories`

返回一级分类。

### `GET /categories/{id}/videos?page=1&pageSize=20`

返回某个分类下的视频列表。会按启用来源聚合搜索；分类可通过 `sourceQueries` 为不同来源指定查询词，并做关键词/排除词/时长过滤。

示例：

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
  "total": 120,
  "items": [
    {
      "id": "v1.xxx",
      "videoKind": "vod",
      "title": "示例视频",
      "description": "简介",
      "creators": ["示例频道"],
      "thumbnailUrl": "https://i0.hdslb.com/...",
      "thumbnailHeaders": {
        "Referer": "https://www.bilibili.com"
      },
      "thumbnailDelivery": "direct",
      "durationSeconds": 3600,
      "playbackKind": "vod"
    }
  ]
}
```

### `GET /search?q=关键词&page=1&pageSize=20`

临时关键词搜索。会调用所有启用来源并过滤明显的解说/混剪/短视频。

### `GET /videos/{id}`

返回详情和最新播放选项。App 进入详情页时应调用此接口。

### `GET /videos/{id}/playback`

仅返回最新播放选项。App 真正进入播放器前应再次调用此接口，以刷新直连播放地址。

可选参数：`delivery=proxy` 可强制本次播放选项走 `/asset` 签名代理下发，用于客户端直连国内源 CDN 失败后的兜底重试；不传时仍按来源默认下发方式返回。

示例（`direct` 国内可直连源）：

```json
{
  "items": [
    {
      "id": "v1.xxx",
      "kind": "vod",
      "label": "720P",
      "url": "https://upos-sz-...bilivideo.com/....mp4",
      "mimeType": "video/mp4",
      "headers": {
        "Referer": "https://www.bilibili.com",
        "Origin": "https://www.bilibili.com"
      },
      "delivery": "direct"
    }
  ]
}
```

示例（`proxy` 国外源经后端代理）：

```json
{
  "items": [
    {
      "id": "v1.xxx",
      "kind": "vod",
      "label": "720P",
      "url": "https://yinghuoren.com/api/media/asset?p=...&sig=...",
      "mimeType": "video/mp4",
      "delivery": "proxy"
    }
  ]
}
```

### `GET /app/update?versionCode=1`

返回 Android TV App 的最新发布版本。若当前版本已是最新，返回 `204 No Content`。

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

下载 admin 发布并审查过的 Android TV App APK。服务端只负责下发已发布文件和元数据；APK 搜集/发布由 admin 管理页完成。

### `GET /asset?p=...&sig=...`

`proxy` 模式资源（缩略图 / 播放流）的后端回源端点。它存在的原因是 **App（国内）无法直连国外源**，
而不是后端无法访问——后端可直连所有网络，回源时一律**直连上游**，不经任何出站代理：

- 仅由后端在 `deliveryMode: "proxy"` 时签发，代理 payload 为认证加密 token，地址带签名(`sig`)和有效期；非后端签发的地址会被拒绝（403/410），不是开放代理，也不会向 App 暴露上游 URL/请求头。
- 支持 `GET` / `HEAD`，并转发 `Range`、`If-Range`、`If-None-Match`、`If-Modified-Since`，支持边下边播和 seek（透传 `206`、`Content-Range`、`Accept-Ranges`）。
- 上游所需请求头（如 `Referer` / `Origin`）由后端回源时附加，不下发给 App。

## 部署相关

- `MEDIA_SERVER_PUBLIC_URL`（可选）：对外可见的 API 基址（如 `https://yinghuoren.com/api/media`），用于拼接 `proxy` 资源的 `/asset` 绝对地址。**位于会改写路径前缀的反向代理后面时必须设置**；未设置时按请求的 `Host` / `X-Forwarded-*` 头推导。
- `MEDIA_SERVER_ASSET_URL_TTL_SECONDS`（可选，默认 21600=6 小时）：`/asset` 代理地址的有效期。
- `contentCategories.json` 的 `sources[]` 可加 `"direct": true|false` 覆盖某个源的下发方式；省略时国内源(bilibili)默认 `direct`，国外源默认 `proxy`。`direct` 仅表示 **App 是否能直连该源**，与后端能否访问无关——后端可访问所有源。

## App 侧必须遵守

1. 不要根据 `thumbnailUrl` 或 `url` 的 host 推断来源逻辑；用 `deliveryMode` 区分 `direct` / `proxy`。
2. `direct` 请求必须带上 companion headers：
   - 图片：`thumbnailHeaders`
   - 播放：`playbackOptions[].headers`
3. `proxy` 请求直接访问后端 `/asset` 地址即可，`headers` 为空，无需也不应附加上游请求头。
4. 播放地址（尤其 `proxy` 的签名代理地址）不要持久化；进入播放器前刷新 `/videos/{id}/playback`。
