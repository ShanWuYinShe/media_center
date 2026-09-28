# Media Server API

本文档描述 Android TV App 对接的公开 API。server 以目录服务为主——从已审核（`approved`）的 catalog 返回视频元数据（id、标题、简介、封面）。播放按 provider 分发：bilibili 由 App 端本地解析（`BilibiliApi`）；youtube 由 server 端解析（实时端点，见下），App 拿直链后自行播放。

## 路径前缀约定

独立站（media.chaoset.com）部署下，本文档所有 `/api/...` 路径同时以两种前缀提供服务：

- **`/api/v1/...`（规范）**：web-service-conventions 约定的版本化前缀，新客户端一律使用；
- **`/api/...`（废弃过渡）**：与 workwork 聚合栈时代的存量 App 及其 nginx 反代兼容，客户端全量切换后移除。

两者行为完全一致（服务端入口归一化，见 `main.go` 的 `apiV1Prefix`）。`GET /healthz` 为无鉴权存活探针，不带版本前缀。App 更新下载基址 `MEDIA_SERVER_PUBLIC_URL` 已指向 `/api/v1`。

## 当前契约

- server 端只从已审核（`approved`）的 catalog 返回数据。
- `GET /api/providers`（规范 `/api/v1/providers`）：有哪些视频源网站。
- `GET /api/providers/{provider}/categories`：某视频源有哪些分类。
- `GET /api/providers/{provider}/categories/{categoryId}/videos`：某分类下已审核的视频列表。

## 错误响应

```json
{ "error": { "code": "BAD_REQUEST", "message": "请求参数不合法。" } }
```

| HTTP 状态 | code | 含义 |
|-----------|------|------|
| 400 | `BAD_REQUEST` | 参数格式不合法 |
| 400 | `QUERY_TOO_LONG` | 搜索关键词超过 128 字节 |
| 401 | `UNAUTHORIZED` | 凭证接口令牌缺失/不匹配，或服务端未配置令牌 |
| 404 | `NOT_FOUND` | 资源不存在（分类、路径等） |
| 429 | `RATE_LIMITED` | 触发 per-IP 限流 |
| 500 | `INTERNAL_ERROR` | 服务端内部错误 |
| 502 | `UPSTREAM_ERROR` / `RESOLVE_FAILED` | youtube 实时端点：vod_extractor(yt-dlp) 上游失败 |
| 503 | `SERVICE_BUSY` | 搜索端点并发满载（保护目录浏览主链路，稍后重试） |
| 503 | `NOT_CONFIGURED` | youtube 实时端点：服务端未配置 API key / 解析服务 |

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

### `GET /api/providers`

返回 catalog 中有 approved 视频的所有视频源。

```json
{ "items": [{ "id": "bilibili", "name": "B站" }] }
```

### `GET /api/providers/{provider}/categories`

返回某视频源下有 approved 视频的分类列表。

```json
{ "items": [{ "id": "movies", "title": "电影", "description": "院线电影" }] }
```

### `GET /api/providers/{provider}/categories/{categoryId}/videos?page=1&pageSize=20`

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

### `GET /api/search?q=关键词&provider=bilibili&page=1&pageSize=20`

在已审核（approved）目录中按关键词子串搜索（匹配标题/简介/作者，大小写不敏感，跨分类去重），按 pinned > score > native_id 排序。**不调任何上游**。`q` 为空返回空结果（不消耗限流配额）；`provider` 可选，非空时只搜索该视频源（App 端只支持 bilibili 播放时用它排除其他 provider 的结果）。

**限流**：本端点匿名可达且是服务端最重的公开查询（每次请求做两次全表扫描），同一 IP 每分钟超过 30 次返回 429 `RATE_LIMITED`；服务端并发搜索满载（保护目录浏览主链路）返回 503 `SERVICE_BUSY`。成功响应带 `Cache-Control: public, max-age=30`。

```json
{ "query": "流浪地球", "total": 2, "items": [ { "id": "BV1xxx", "title": "流浪地球", ... } ] }
```

### `GET /api/providers/credentials`

返回 admin 配置的 provider 凭证（如 bilibili SESSDATA），App 端用于请求高分辨率内容。

**鉴权**：请求必须携带 `X-App-Token` 头，且与服务端 `MEDIA_SERVER_APP_API_TOKEN` 一致（常量时间比较）；未配置 token 或令牌不匹配返回 401。同一 IP 每分钟超过 30 次返回 429。无凭证时返回空数组：

> **风险告知**：`APP_API_TOKEN` 构建期注入 BuildConfig 随 APK 分发，反编译可提取。当前按**自用 App** 接受该风险（per-IP 限流 + 无设备绑定），若未来公开发布需改为服务端下发短期令牌或设备绑定方案。

```json
{ "credentials": [] }
```

### `GET /api/providers/youtube/search?q=关键词`

YouTube **实时搜索**：经 vod_extractor 的 yt-dlp `ytsearch`（flat 模式抓搜索结果页）。与观众浏览器走同一条 YouTube 公开前端，**无 Data API 每日配额**；代价是单次搜索耗时秒级，且与播放解析同样依赖 cookies。结果不写 catalog，仅供 App 即时展示与播放——审核关卡只约束进入分类列表的 catalog 数据，此端点不绕过它（不产生任何入库）。

**鉴权/限流**：同凭证接口的 `X-App-Token` 要求；同一 IP 每分钟 8 次。单页固定 20 条，无翻页令牌（TV 搜索每键即时出结果，够用）。

```json
{
  "query": "经典电影",
  "total": 1000000,
  "items": [
    { "id": "jNQXAC9IVRw", "title": "频道: 标题", "description": "…", "thumbnailUrl": "https://i.ytimg.com/...", "durationSeconds": 19 }
  ],
  "nextPageToken": "CAUQAA"
}
```

- 未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 返回 503 `NOT_CONFIGURED`；yt-dlp 失败（cookies 失效、风控等）返回 502 `UPSTREAM_ERROR`。

### `GET /api/providers/youtube/resolve?id=<11字符视频ID>`

YouTube 播放解析：代理 `vod_extractor`（yt-dlp）解析 watch URL，返回该视频全部可播放清晰度档位（progressive mp4 优先，其次 HLS；按高度降序）。App 端一次拿到全部档位，切清晰度在播放器内本地换源，不需要重新解析。

**鉴权/限流**：`X-App-Token` 同上；同一 IP 每分钟 20 次。端到端超时 40s（yt-dlp 解析需秒级时间）。

```json
{
  "options": [
    { "id": "yt-jNQXAC9IVRw-22", "kind": "vod", "label": "720P", "url": "https://…googlevideo.com/…",
      "mimeType": "video/mp4", "headers": {}, "qn": 720 },
    { "id": "yt-jNQXAC9IVRw-308", "kind": "vod", "label": "1080P60", "url": "https://…/playlist.m3u8",
      "mimeType": "application/x-mpegurl", "headers": {}, "qn": 1080 }
  ],
  "availableQualities": [ { "qn": 1080, "label": "1080P60" }, { "qn": 720, "label": "720P" } ],
  "durationSeconds": 19
}
```

- `options` 按清晰度从高到低排列，App 取首项播放；`mimeType` 为 `application/x-mpegurl` 时按 HLS 加载。
- 未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 返回 503 `NOT_CONFIGURED`；解析失败（视频受限、cookies 失效、无可用格式）返回 502 `RESOLVE_FAILED`。
- **部署硬前提**：`.wework/vod_extractor/youtube-cookies.txt` 必须存在，否则 YouTube 对数据中心 IP 的解析一律返回 bot 检测失败。

### `GET /api/app/update?versionCode=1&variant=normal`

返回 Android TV App 的最新发布版本。已是最新返回 `204 No Content`。

- `versionCode`：当前已安装版本号，服务端仅当 manifest 版本更新时返回 200。
- `variant`：可选，App 变体（`normal` 或 `legacy`）。非空时读取 `app-update-{variant}.json` 对应变体的发布清单；省略时读默认清单（`app-update.json`）。

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://media.chaoset.com/api/v1/app/download",
  "sizeBytes": 12345678,
  "sha256": "64 字符 SHA-256 hex",
  "releaseNotes": "修复播放失败后跳回首页的问题",
  "publishedAt": "2026-06-14T04:00:00Z"
}
```

### `GET /api/app/download?variant=normal`

下载 admin 发布并审查过的 Android TV App APK。`variant` 语义同上，非空时下载对应变体的 APK。

### `POST /api/crash/report`

接收 App 端崩溃上报，落盘到 `MEDIA_SERVER_CRASH_DIR`（默认 `data/crash-reports/crash.log`，JSONL，超 8MB 滚动）。请求体（JSON，≤512KB）：

```json
{
  "packageName": "com.example.mediacenter",
  "appVersionName": "1.2.0",
  "appVersionCode": 1002000,
  "osVersion": "Android 13",
  "device": "Xiaomi/小米电视",
  "stackTrace": "java.lang.NullPointerException: ...",
  "occurredAt": "2026-08-01T04:00:00Z"
}
```

成功返回 `{"status":"ok"}`；`stackTrace` 为空或 JSON 非法返回 400；同一 IP 每分钟超过 10 条返回 429。

## 部署相关

- `MEDIA_SERVER_PUBLIC_URL`（建议必填）：对外可见的 API 基址（如 `https://media.chaoset.com/api/v1`）。位于反代后面时必须设置；未设置时下载地址由 Host 推导，仅接受 `MEDIA_SERVER_ALLOWED_HOSTS` 白名单或本地网段（防 Host 头投毒）。
- `MEDIA_SERVER_APP_API_TOKEN`：凭证接口与 youtube 实时端点的令牌，与 App 构建时注入的 `APP_API_TOKEN` 一致；为空则这些接口 401。
- `MEDIA_SERVER_VOD_EXTRACTOR_URL`：vod_extractor 内网基址（如 `http://vod_extractor:8790`）；为空时 youtube 的搜索/解析端点均返回 503。
- `contentCategories.json` 定义分类 ID 到标题/描述的映射。sources 字段保留解析（向后兼容旧配置）但不再使用。

## App 侧必须遵守

1. `VideoSummary.id` 是 catalog 的 nativeId（如 BV1xxx 或 YouTube 11 字符视频 ID）。App 按 provider 分发：bilibili 调本地解析器，youtube 调 `/api/providers/youtube/resolve`。
2. App 端支持 provider 切换 UI（首页顶栏，`/api/providers` 返回多个时显示）；当前 `bilibili` 与 `youtube` 有播放实现，其他 provider 返回"暂不支持在线播放"的明确错误。
3. bilibili 缩略图 URL 是 B 站 CDN 链接，App 直连时需带 `Referer: https://www.bilibili.com` 请求头；YouTube 缩略图（i.ytimg.com）无需特殊请求头。
