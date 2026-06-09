# Media Server API

本文档描述 Android TV App 对接的公开 API。

## Base URL

App 应把配置值当作 **API base URL**。当前线上默认是：

`https://yinghuoren.com/api/media`

## 当前契约

- 后端负责：分类/搜索/详情/播放选项聚合，以及为 App 提供**直连上游所需请求头**。
- 对 App **可直接访问**的国内资源，后端会直接返回上游 `thumbnailUrl` / `playbackOptions[].url`。
- 对 App 未来**不能直连**的来源，后端仍可以返回后端 URL；因此 App 必须把 URL 当成**可执行地址**，不要自行推断来源。
- `thumbnailHeaders` 和 `playbackOptions[].headers` 必须原样带到图片请求和播放请求里。
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
  thumbnailHeaders?: Record<string, string>;
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

返回某个分类下的视频列表。当前会基于该分类的 Bilibili 查询词搜索并做关键词/排除词/时长过滤。

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
      "durationSeconds": 3600,
      "playbackKind": "vod"
    }
  ]
}
```

### `GET /search?q=关键词&page=1&pageSize=20`

临时关键词搜索。当前直接调用 Bilibili 搜索接口并过滤明显的解说/混剪/短视频。

### `GET /videos/{id}`

返回详情和最新播放选项。App 进入详情页时应调用此接口。

### `GET /videos/{id}/playback`

仅返回最新播放选项。App 真正进入播放器前应再次调用此接口，以刷新直连播放地址。

示例：

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
      }
    }
  ]
}
```

## App 侧必须遵守

1. 不要根据 `thumbnailUrl` 或 `url` 的 host 推断来源逻辑。
2. 直连请求必须带上 companion headers：
   - 图片：`thumbnailHeaders`
   - 播放：`playbackOptions[].headers`
3. 播放地址不要持久化；进入播放器前刷新 `/videos/{id}/playback`。
