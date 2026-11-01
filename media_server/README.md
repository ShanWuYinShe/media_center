# Media Server

Go 实现的目录服务：catalog 只提供已审核（`approved`）数据，不做实时上游采集；
youtube provider 是受控例外——实时搜索与播放直链解析经内网 `vod_extractor`
（yt-dlp）代理完成，结果不落 catalog。播放按 provider 处理：bilibili 由 App
端本地解析，youtube 由 server 端解析后下发全部清晰度档。

## 当前架构

- 目录服务从已审核（`approved`）的 catalog（SQLite）提供 provider 列表、分类列表、视频元数据。
- `GET /api/providers`：有哪些视频源。
- `GET /api/providers/{provider}/categories`：某视频源有哪些分类。
- `GET /api/providers/{provider}/categories/{categoryId}/videos`：某分类下已审核的视频列表（分页；可选 `minDurationSeconds`/`maxDurationSeconds`）。
- `GET /api/providers/{provider}/videos/{videoId}/collection`：视频所在合集的已审核剧集队列（App 片尾自动连播）。
- youtube provider 额外提供实时端点（见 `youtube.go`，均经 vod_extractor）。

## API

完整 App 对接说明见 [`API.md`](./API.md)。

- `GET /api/health`（亦接受 `/health`）
- `GET /api/providers`
- `GET /api/providers/{provider}/categories`
- `GET /api/providers/{provider}/categories/{categoryId}/videos`
- `GET /api/search?q=…`：在已审核目录中按关键词搜索（标题/简介/作者，跨分类去重）。
- `GET /api/providers/credentials`：provider 凭证（如 bilibili SESSDATA），**需 `X-App-Token`**（见下）。
- `GET /api/providers/youtube/search?q=…`：youtube 实时搜索（不写 catalog），**需 `X-App-Token`**。
- `GET /api/providers/youtube/resolve?id=<videoId>`：youtube 播放直链解析（返回全部清晰度档），**需 `X-App-Token`**。
- `GET /api/app/update?versionCode=N&variant=<normal|legacy>`：App 更新检查（variant 缺省 normal）。
- `GET /api/app/download`：更新 APK 下载（绕过 API 超时包裹，流式发送）。
- `POST /api/crash/report`：App 崩溃上报落盘。

## 配置与部署

- `CONTENT_CATEGORIES_FILE`：运行时分类配置。独立站部署由 `deploy/data/generated/contentCategories.json` 挂载为 `/config/contentCategories.json`（编辑后 `make restart media_server` 生效）。
- `MEDIA_SERVER_CATALOG_DB`：SQLite 数据库路径，默认 `data/catalog.db`。设为空则关闭 catalog。
- `MEDIA_SERVER_PUBLIC_URL`：**建议必填**，对外可见 API 基址（如 `https://media.chaoset.com/api/v1`），用于拼接 app download URL。未配置时下载地址由请求 Host 推导，仅接受 `MEDIA_SERVER_ALLOWED_HOSTS` 白名单或本地网段——防止 Host 头投毒把更新包指向攻击者域名。
- `MEDIA_SERVER_ALLOWED_HOSTS`：逗号分隔的公网域名白名单，仅当 `MEDIA_SERVER_PUBLIC_URL` 未配置时用于校验下载 Host。
- `MEDIA_SERVER_APP_API_TOKEN`：凭证等 App 端接口令牌（`X-App-Token`）。为空时 `/api/providers/credentials` 一律 401（App 降级无凭证模式）。App 构建时通过 gradle 属性 `APP_API_TOKEN` 注入同一值。
- `MEDIA_SERVER_VOD_EXTRACTOR_URL`：vod_extractor 内网基址（如 `http://vod_extractor:8790`），youtube 实时搜索/解析依赖；为空时 youtube provider 的实时端点不可用。
- `HOST` / `PORT`：监听地址，默认 `0.0.0.0:8787`。
- `MEDIA_SERVER_INTERNAL_TOKEN`：内部管理接口令牌，用于 `/internal/catalog/*` 管理接口认证。为空时整个 `/internal/*` 路由树返回 404（安全默认）。
- `PROVIDER_CREDENTIALS_FILE`（别名 `MEDIA_SERVER_PROVIDER_CREDENTIALS_FILE`）：provider 凭证 JSON 路径（如 bilibili SESSDATA）。为空时不加载凭证，App 走无凭证模式。
- `MEDIA_SERVER_APP_UPDATE_MANIFEST`：App 更新清单路径，默认 `/config/app-update.json`。
- `MEDIA_SERVER_APP_UPDATE_APK`：App 更新 APK 路径，默认 `/config/media-center-app.apk`。
- `MEDIA_SERVER_CRASH_DIR`：App 崩溃上报落盘目录，默认 `data/crash-reports`。

## 合集同步（B 站 ugc_season）

合集只存分组关系，不新增视频、不改审核状态；公开队列只返回其中 `approved` 的剧集。
服务器 IP 访问 B 站会被风控，采集脚本应在可访问 B 站的开发机运行，经 SSH 隧道写入：

```bash
# 服务器容器 IP
IP=$(ssh workwork-server "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' media_center-media_server-1")
ssh -N -L 18787:$IP:8787 workwork-server &
TOKEN=$(ssh workwork-server "grep '^MEDIA_SERVER_INTERNAL_TOKEN=' ~/media_center/deploy/.env | cut -d= -f2-")
MEDIA_SERVER_INTERNAL_TOKEN="$TOKEN" scripts/sync-bilibili-collections.py \
  --catalog-url http://127.0.0.1:18787/api/v1 \
  --internal-url http://127.0.0.1:18787 --push
```

脚本从 B 站 `view.ugc_season` 提取合集与有序剧集，按 `episodes` 原始顺序写入；同合集其他剧集会被自动跳过，减少 B 站请求。`--dry-run` 只统计，`--output` 可先导出快照。

## 数据模型

分类浏览与搜索只返回 `approved` 数据，**不做实时上游搜索/采集**。候选经 admin `/internal/catalog/import` 导入 → 人工 + AI 审核 → 上线 `approved`。`VideoSummary` 只有五个字段：`id`（nativeId）、`title`、`description`、`thumbnailUrl`、`durationSeconds`。
