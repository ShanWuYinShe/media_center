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

### `GET /healthz`（无版本前缀）

无鉴权存活探针，同时可诊断 catalog 状态。公开路径 `/health`、`/api/health` 行为一致。

```json
{ "status": "ok", "version": "v1.1.0-3-gabc1234", "catalog": "ok" }
```

`catalog` 取值：`ok`（数据库已打开）、`disabled`（未配置 DB）、`error`（配置了 DB 但打开失败）。`error` 时 HTTP 状态为 **503**，`status` 为 `degraded`——服务仍能响应空目录，但这是必须告警的故障态。

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

`youtube` 是例外：内容不入 catalog，分类由服务端内置（音乐/科技/游戏/纪录片/电影/学习）；
未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 时返回空数组。分类 ID 形如 `youtube-music`。

### `GET /api/providers/{provider}/categories/{categoryId}/videos?page=1&pageSize=20&minDurationSeconds=600&maxDurationSeconds=2699`

返回某分类下已审核的视频列表（分页，按 pinned > rank > score 排序）。

`minDurationSeconds` / `maxDurationSeconds` 为可选的时长筛选（单位秒，**含边界**），用于「短片/中片/长片」等档位；两者可单独使用。启用筛选时时长缺失的视频会被排除，`total` 为筛选后的总数。参数非数字、负数或 `min > max` 返回 400 `BAD_REQUEST`。

`provider=youtube` 时该端点把分类映射为一次实时 ytsearch：需要 `X-App-Token`，与 YouTube 搜索共用每 IP 每分钟 12 次限流；只支持第 1 页（`page=2` 返回空页），`total` 为本次返回条数，时长筛选参数被忽略。

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

### `GET /api/providers/{provider}/videos/{videoId}/collection`

返回该视频所在合集（如 B 站 `ugc_season`）的**已审核**剧集播放队列，供 App 片尾自动连播。

```json
{
  "collection": { "id": "6435849", "title": "大熊DOTA", "total": 2 },
  "currentIndex": 0,
  "items": [
    { "id": "BV1a", "title": "EP1", "durationSeconds": 1714 },
    { "id": "BV1c", "title": "EP3", "durationSeconds": 2179 }
  ]
}
```

- `items` 只包含 `approved` 剧集，顺序与来源合集列表一致；未过审剧集不会出现（审核关卡不被绕过）。
- `currentIndex` 是当前视频在 `items` 中的下标；当前视频未过审/不在队列时为 `-1`。
- 视频不在任何合集，或合集内没有已审核剧集时返回 `{"collection":null,"currentIndex":-1,"items":[]}`（200，不是错误）。
- 合集关系由采集工具通过内部接口写入，不属于公开 API。

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

YouTube **实时搜索**：经 vod_extractor 的 yt-dlp `ytsearch`（flat 模式抓搜索结果页）。与观众浏览器走同一条 YouTube 公开前端，**无 Data API 每日配额**；代价是单次搜索耗时秒级。flat 列表页不触发播放风控，**无 cookies 仍可用**（2026-10-12 线上实测）；播放解析才必须带 cookies。结果不写 catalog，仅供 App 即时展示与播放——审核关卡只约束进入分类列表的 catalog 数据，此端点不绕过它（不产生任何入库）。

**鉴权/限流**：同凭证接口的 `X-App-Token` 要求；同一 IP 每分钟 12 次。单页固定 20 条，无翻页令牌（TV 搜索每键即时出结果，够用）。

```json
{
  "query": "经典电影",
  "total": 20,
  "items": [
    { "id": "jNQXAC9IVRw", "title": "频道: 标题", "thumbnailUrl": "https://i.ytimg.com/...", "durationSeconds": 19 }
  ]
}
```

`total` 是本次返回条数（ytsearch 单页固定 20 条），不返回 `nextPageToken`。

- 未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 返回 503 `NOT_CONFIGURED`；yt-dlp 失败（cookies 失效、风控等）返回 502 `UPSTREAM_ERROR`。

### `GET /api/providers/youtube/resolve?id=<11字符视频ID>`

YouTube 播放解析：代理 `vod_extractor`（yt-dlp）解析 watch URL，返回该视频全部可播放清晰度档位（按高度降序）。App 端一次拿到全部档位，切清晰度在播放器内本地换源，不需要重新解析。

**格式选择**：每档高度只保留一条最佳候选（H.264 > VP9 > AV1，其次帧率，再优先音视频合一的 progressive/HLS）。YouTube 的 1080P+ 档位通常只有纯视频轨 + 纯音频轨：此时 `url` 是视频轨、`audioUrl` 是最高码率音轨，App 需用 `MergingMediaSource` 合流播放；没有可用音轨的纯视频格式不会下发（避免无声视频）。

**鉴权/限流**：`X-App-Token` 同上；同一 IP 每分钟 20 次。端到端超时 70s（Deno 签名求解 + PO Token 冷启动实测 25–30s）。

```json
{
  "options": [
    { "id": "yt-jNQXAC9IVRw-22", "kind": "vod", "label": "720P", "url": "https://…googlevideo.com/…",
      "mimeType": "video/mp4", "headers": {}, "qn": 720 },
    { "id": "yt-jNQXAC9IVRw-137-a140", "kind": "vod", "label": "1080P", "url": "https://…/videoplayback…",
      "audioUrl": "https://…/videoplayback…", "mimeType": "video/mp4", "headers": {}, "qn": 1080 }
  ],
  "availableQualities": [ { "qn": 1080, "label": "1080P60" }, { "qn": 720, "label": "720P" } ],
  "durationSeconds": 19
}
```

- `options` 按清晰度从高到低排列，App 取首项播放；`mimeType` 为 `application/x-mpegurl` 时按 HLS 加载。
- `audioUrl` 存在时代表音视频分离：`url` 是视频轨、`audioUrl` 是音轨，`audioMimeType` 为音轨容器（`audio/mp4`/`audio/webm`），App 必须合流播放。
- 未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 返回 503 `NOT_CONFIGURED`；解析失败（视频受限、cookies 失效、无可用格式）返回 502 `RESOLVE_FAILED`。
- **部署硬前提**：`deploy/data/vod/youtube-cookies.txt`（容器内只读挂载为 `/config/youtube-cookies.txt`）必须存在，否则 YouTube 对数据中心 IP 的播放解析一律返回 bot 检测失败；搜索（flat 列表页）不受影响。路径与过期重导出运维以 `vod_extractor/AGENTS.md` 为准。

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
