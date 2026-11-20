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
| 502 | `UPSTREAM_ERROR` / `RESOLVE_FAILED` | youtube 实时端点：**内容类**失败（视频受限/地区限制/私有/无可用格式），重试无效 |
| 503 | `SERVICE_BUSY` | 并发满载：catalog 搜索闸门，或 youtube 实时端点排队等不到 vod_extractor 并发槽（带 `Retry-After`，稍后重试即可） |
| 503 | `RESOLVE_UNAVAILABLE` / `UPSTREAM_UNAVAILABLE` | youtube 实时通道**基础设施类**故障（cookies 失效/风控/extractor 不可达/超时/配置错误）：带 `Retry-After`，可安全重试；不按视频负缓存，连续失败会触发进程级熔断（见 `/healthz` 的 `youtube` 字段） |
| 503 | `CONTENT_PREPARING` | youtube 分类端点 12s 服务端预算内未拿到结果（冷缓存）：带 `Retry-After: 5`，后台单飞会继续填充缓存，稍后重试直接命中 |
| 503 | `CATALOG_UNAVAILABLE` | catalog 暂不可用（启动打开失败等）：目录相关端点返回 503（`no-store`）而不是 200 空目录，客户端可重试；服务端会后台退避重开（成功后自动恢复、无需重启进程），`/healthz` 显示 `catalog=error` 与具体原因 |
| 503 | `UPDATE_UNAVAILABLE` | App 更新清单损坏（JSON/sha256 非法）或 APK 缺失：更新检查返回 503 而不是静默 204，`/healthz` 显示 `appUpdate=error` |
| 410 | `APK_VERSION_GONE` | APK 下载的版本令牌 `v` 与当前 manifest 不匹配（发版后旧地址）：客户端应重新检查更新，不再返回当前 APK |
| 503 | `TIMEOUT` | 单个 API 请求超过服务端 75s 硬超时（防慢查询/卡住 handler 拖垮服务；超时响应为同一 JSON 包络，可安全重试） |
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
  thumbnailUrl?: string;       // 卡片尺寸缩略图（B 站图床为 ~640px CDN 变体）
  thumbnailUrlLarge?: string;  // 详情页大图；未知图床与 thumbnailUrl 相同
  durationSeconds?: number;
  year?: string;               // 发布年份（目录数据才有，youtube 实时搜索可能省略）
};
```

## Endpoints

### `GET /healthz`（无版本前缀）

无鉴权存活探针，同时可诊断 catalog 状态。公开路径 `/health`、`/api/health` 行为一致。

```json
{
  "status": "ok",
  "version": "v1.1.0-3-gabc1234",
  "catalog": "ok",
  "appUpdate": "ok",
  "cache": {
    "resolveMemoryHits": 0,
    "resolveDiskHits": 0,
    "resolveUpstreams": 0,
    "searchHits": 0,
    "categoryHits": 0,
    "warmRuns": 0,
    "resolveLastMs": 0,
    "resolveAvgMs": 0,
    "resolveCount": 0,
    "searchLastMs": 0
  },
  "youtube": {
    "circuit": "closed",
    "consecutiveFailures": 0,
    "lastReason": "",
    "retryAfterSeconds": 0
  }
}
```

`cache` 是进程内缓存命中/上游调用计数（阶段 0 观测）：`resolveMemoryHits`/`resolveDiskHits`
为 YouTube 播放解析的内存/磁盘缓存命中次数，`resolveUpstreams` 为真实调用 vod_extractor 的次数，
`searchHits`/`categoryHits` 为 YouTube 实时搜索/分类快照缓存命中；`warmRuns` 为后台预热真实完成的上游刷新次数（缓存命中或加入用户在飞请求不计）——预热会强制打上游续期，该计数是「缓存是否被持续保温」的直接证据。
进程重启归零，仅用于验证缓存是否在用户路径生效。
`resolveLastMs`/`resolveAvgMs`/`resolveCount` 为真实上游解析的最近/平均/累计耗时（毫秒），
`searchLastMs` 为最近一次上游搜索（含分类预热）耗时——用于判断"慢在解析还是慢在缓存失效"。

`catalog` 取值：`ok`（数据库已打开）、`disabled`（未配置 DB）、`error`（配置了 DB 但打开失败）。`error` 时 HTTP 状态为 **503**，`status` 为 `degraded`，并附 `catalogError` 文字原因——服务仍能响应 503，但这是必须告警的故障态；服务端每 30s→5min 退避重开数据库，成功后自动恢复 `ok`，无需重启进程。

`appUpdate` 是 App 更新通道状态：`ok`（清单与 APK 均可用）、`missing`（未发布过更新，检查更新返回 204）、`error`（清单损坏/sha256 非法/APK 缺失，检查更新返回 503 `UPDATE_UNAVAILABLE`）。

`youtube` 是 YouTube 实时通道的熔断状态（仅在配置了 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 时出现）：`circuit=open` 表示连续基础设施失败（cookies 失效/风控/extractor 不可达等）已触发快失败，`retryAfterSeconds` 为剩余冷却时间；冷却后半开放行探测请求，成功后自动恢复 `closed`。看到 `open` 应优先检查 extractor 容器、cookies 与上游网络，而不是怀疑某个视频。

### `GET /api/providers`

返回 catalog 中有 approved 视频的所有视频源。

```json
{ "items": [{ "id": "bilibili", "name": "B站" }] }
```

### `GET /api/providers/{provider}/bootstrap`

App 首页启动聚合端点：一次返回 providers 与指定 provider 的分类，替代客户端串行请求
`/providers` + `/providers/{provider}/categories`（省一个 RTT）。响应与两个子端点字段同构，
任一子查询失败即整体 500（不返回半份数据）。

```json
{
  "provider": "bilibili",
  "providers": [{ "id": "bilibili", "name": "B站" }],
  "categories": [{ "id": "movies", "title": "电影", "description": "院线电影" }]
}
```

`provider` 原样回显请求值；`youtube` 的分类规则与 `/categories` 相同（内置映射，
未配置 vod_extractor 时为空数组）。`Cache-Control: public, max-age=60`。

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

`provider=youtube` 时该端点把分类映射为一次实时 ytsearch：需要 `X-App-Token`，与 YouTube 搜索共用每 IP 每分钟 12 次限流，但**缓存命中不计数**（翻回刚看过的分类不会 429）；只支持第 1 页（`page=2` 返回空页），`total` 为本次返回条数，时长筛选参数被忽略。分类限流的文案是"分类加载过于频繁"（与搜索区分）；yt-dlp 并发槽满时同样映射 503 `SERVICE_BUSY` + `Retry-After`。
服务端给该端点 **12s 预算**（短于两端 App 目录客户端的 15s 读超时）：预算内没拿到结果返回 503 `CONTENT_PREPARING` + `Retry-After: 5`，后台单飞继续跑完并填充缓存，App 稍后重试即可命中，不会看到"网络错误"。

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
- 队列下发上限 **500 集**（`collection.total` 仍为真实总集数）：该端点只服务"播完自动下一集"，超大合集全量下发会让响应体随集数线性增长；客户端应只依赖 `currentIndex` 与相邻项。
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

**鉴权/限流**：同凭证接口的 `X-App-Token` 要求；同一 IP 每分钟 12 次，但**只对真正要打 yt-dlp 的未命中请求计数**——缓存命中的热词搜索是纯内存操作，不会因反复点击而被 429。单页固定 20 条，无翻页令牌（TV 搜索每键即时出结果，够用）。vod_extractor 并发槽满时服务端会先排队/短退避重试，仍不可用返回 503 `SERVICE_BUSY` + `Retry-After: 10`（App 显示"服务繁忙"而不是"视频受限"）。

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

- 未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 返回 503 `NOT_CONFIGURED`；yt-dlp 失败（cookies 失效、风控、extractor 不可达/超时等基础设施类）返回 503 `UPSTREAM_UNAVAILABLE` + `Retry-After: 15`（可安全重试；连续失败会触发进程级熔断，见 `/healthz` 的 `youtube` 字段）。旧版 extractor 的裸 502（无 `reason`）保留 502 `UPSTREAM_ERROR` 兼容。

### `GET /api/providers/bilibili/search?q=&page=1`

B 站**全站**实时搜索：服务端代理 B 站官方搜索接口（WBI 签名），按关键词返回全站视频。要求 `X-App-Token`，限流 20/min/IP；结果带 30s `Cache-Control`，服务端另有 5min 按页缓存。

**与 `/api/search` 的区别**：`/api/search` 只在已审核目录内做关键词过滤（收录范围小）；本端点搜的是 B 站全站（与用户在 B 站网页里搜索同源）。App 的 B 站搜索应走本端点。

响应结构同 `SearchResponse`：`total` 为 B 站返回的命中总数，`items` 为当前页（每页 20 条，`id` 为 bvid，可直接用于播放解析）；`title` 已去掉高亮标签并补上「UP主: 标题」前缀。结果**不写 catalog**（与 youtube 实时通道同一受控例外）。

### `GET /api/providers/youtube/thumb/{videoID}/{quality}.jpg`

YouTube 缩略图代理：服务端代取 `i.ytimg.com` 图片并返回字节（国内直连 YouTube 图床不可达，电视上封面会裂图）。

- `videoID`：11 字符视频 ID（大小写敏感，与播放解析同一格式校验）。
- `quality`：`hq`（480px，所有视频必有）/ `sd`（640px，缺失时回退 hq）/ `maxres`（1280px，缺失时依次回退 sd → hq）。
- 响应 `image/jpeg`，带 `Cache-Control: public, max-age=7d, immutable`（URL 以 .jpg 结尾，Cloudflare 默认静态缓存生效）；服务端另有进程内 LRU（96 条）避免反复回源。
- 全部候选缺失返回 404（`no-store`）。无鉴权——URL 形态固定、不能代理任意地址，不是开放代理。

**youtube 实时搜索/分类结果里的 `thumbnailUrl`（hq）与 `thumbnailUrlLarge`（maxres）已自动改写为本端点地址**（配置了 `MEDIA_SERVER_PUBLIC_URL` 时），App 无需发版即可修复封面裂图；未配置公网基址时保持原始地址（本地开发）。

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
- 失败语义按原因分类（App 应据此提示，不要一律说"视频受限"）：
  - **内容类**（`unavailable`/`geo_blocked`/`private`/`no_formats`）→ 502 `RESOLVE_FAILED`，按视频负缓存 5 分钟（这类失败重试无效）；旧版 extractor 的裸 502（无 `reason`）也按内容类兼容。
  - **基础设施类**（`bot_check`/`rate_limited`/`timeout`/cookies 或 extractor 连接/配置错误）→ 503 `RESOLVE_UNAVAILABLE` + `Retry-After: 15`，**不写视频级负缓存**；连续 3 次基础设施失败打开进程级熔断（冷却 60s，`/healthz.youtube` 可见），避免用户换视频重试把 extractor 并发槽打满、连带搜索/分类不可用。
  - **容量繁忙**（extractor 槽满排队+短退避重试后仍 busy）→ 503 `SERVICE_BUSY` + `Retry-After: 10`（不写负缓存）。
- 限流与缓存：内存/磁盘缓存命中不消耗 per-IP 配额；只有真正要打 yt-dlp 的未命中请求才计数（熔断打开时直接快失败、不计数）。
- 未配置 `MEDIA_SERVER_VOD_EXTRACTOR_URL` 返回 503 `NOT_CONFIGURED`。
- **部署硬前提**：`deploy/data/vod/youtube-cookies.txt`（容器内只读挂载为 `/config/youtube-cookies.txt`）必须存在，否则 YouTube 对数据中心 IP 的播放解析一律返回 bot 检测失败；搜索（flat 列表页）不受影响。路径与过期重导出运维以 `vod_extractor/AGENTS.md` 为准。

### `GET /api/preferred-ips`

下发 Cloudflare 优选 IP 候选池，供客户端自己测速后选择最快节点。

**为什么需要**：服务端挂在 Cloudflare 后面，国内运营商 DNS 常把 `media.chaoset.com`
解析到绕路或被 QoS 的 anycast 节点。实测同一域名走系统 DNS：15 次请求 8 次建立
不了连接、成功时首字节 3.7s；绑定一个优选 IP 后 15/15 成功、首字节 1.0s。

**职责划分**：**测速必须由客户端做**——只有客户端知道自己到各节点的真实 RTT，
TV 所在网络与服务器出口完全不是一回事。服务端只提供候选池（服务端在墙外，能稳定
拉取公共优选源；公共源在境内直连普遍超时/被污染），这样换 IP 不需发版。

响应：

```json
{
  "items": ["103.31.4.1", "104.26.0.1", "..."],
  "updatedAt": "2026-11-20T14:00:00Z",
  "cacheSeconds": 3600
}
```

- `items`：候选 IPv4 列表（纯字面量，不含端口/协议）。顺序仅供参考，**客户端必须
  自己测速排序**，不要直接取第一个当最优。
- `updatedAt`：候选池最近一次成功刷新的时间（RFC3339）；缺失表示仍在用内置种子池。
- `cacheSeconds`：客户端可缓存时长，正常每天最多请求一两次。

特性与约束：

- 无鉴权、不含敏感信息；带 `Cache-Control: public, max-age=1800`，边缘/客户端可缓存。
- 候选池由服务端后台每小时从公共优选源刷新（失败保留旧池，最终兜底为内置种子池），
  因此 **`items` 永远非空**。
- 仅 IPv4。客户端只应把该池用于 `media.chaoset.com` 这一个域名的 DNS 覆盖，
  其它域名必须回落到系统 DNS（否则会劫持图床/上游 API）。
- 这是**纯加速辅助端点**：任何失败都不应影响 App 的目录浏览与播放，客户端必须保留
  「取不到候选 → 用内置池 → 再不行用系统 DNS」的降级链。

### `GET /api/app/update?versionCode=1&variant=normal`

返回 Android TV App 的最新发布版本。已是最新返回 `204 No Content`。

- 清单文件不存在 → 204（等价于"没有发布过更新"）；清单损坏（JSON/sha256 非法）或 APK 缺失 → **503 `UPDATE_UNAVAILABLE`**（`no-store`），不再静默 204，`/healthz` 的 `appUpdate` 会显示 `error`。

- `versionCode`：当前已安装版本号，服务端仅当 manifest 版本更新时返回 200。
- `variant`：可选，App 变体（`normal` 或 `legacy`）。非空时读取 `app-update-{variant}.json` 对应变体的发布清单；省略时读默认清单（`app-update.json`）。

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://media.chaoset.com/api/v1/app/download?variant=&v=1a2b3c4d5e6f",
  "sizeBytes": 12345678,
  "sha256": "64 字符 SHA-256 hex",
  "releaseNotes": "修复播放失败后跳回首页的问题",
  "publishedAt": "2026-06-14T04:00:00Z"
}
```

### `GET /api/app/download?variant=normal&v=<sha256 前 12 位>`

下载 admin 发布并审查过的 Android TV App APK。`variant` 语义同上，非空时下载对应变体的 APK。

`v` 是版本令牌（manifest `sha256` 的前 12 位），由 `/api/app/update` 下发的 `apkUrl` 自动携带：同一版本重新打包也会变，所以 **带 `v` 的 URL 内容不可变**。`v` 与当前 manifest 不匹配（发版/重新打包后的旧地址）返回 **410 `APK_VERSION_GONE`**，客户端应重新检查更新，而不是下载一份 sha256 对不上的新包；缺省 `v` 的旧地址仍可下载但 `no-store`。匹配时服务端返回
`Cache-Control: public, max-age=31536000, immutable` + `ETag`，可被 CDN/浏览器长缓存。缺省 `v` 或 `v` 与当前 manifest 不匹配（旧客户端/发版后的旧地址）时返回 `Cache-Control: no-store`，避免把旧 APK 发给新版本客户端。

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
