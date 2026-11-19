# media 站部署（media.chaoset.com）

`media_server` + `vod_extractor` 的独立站编排。与 workwork 聚合栈零互通：
独立 bridge、独立数据目录、独立版本来源（[versions.env](versions.env)）。
API 约定见仓库根 AGENTS.md「API 约定」：业务接口规范前缀 `/api/v1/`，
旧 `/api/*` 为过渡期别名；`GET /healthz` 无鉴权存活探针。

## 首次部署（服务器）

1. **IPv6 寻址**：确认宿主 ipvlan_docker 网络可分配新地址（`ip -6 route` 查看
   前缀；与 workwork 的 NGINX_IPV6/XRAY_IPV6 同池不同址）。写入 `.env` 的
   `MEDIA_IPV6`，并在 Cloudflare 加 `media.chaoset.com` AAAA 记录指向该地址
   （DNS 操作须在服务器上执行——CF token 有来源 IP 白名单；token 归档在
   `~/work/dogod/cf_token_and_cert/`）。
2. **证书**：`cert/` 放 `server_cert.pem` / `server_cert.key`（已有
   `*.chaoset.com` 通配符 Origin 证书，有效期至 2041-09，备份同上路径，无需重签）
   与 `cloudflare-origin-pull.pem`（CF Origin Pull CA，同在备份）。
3. **CF zone 设置**：确认 chaoset.com 已开启 Authenticated Origin Pulls
   （nginx `ssl_verify_client on` 依赖 CF 侧回源出示客户端证书；zone 未开启时
   所有请求 400。服务器上用 token：
   `PATCH /zones/{zone_id}/settings/tls_client_auth {"value":"on"}`）。同时确认
   `media` 子域记录为橙云代理（边缘 TLS + 回源 mTLS 的前提）。
4. **数据就位**：
   - `data/generated/`：`contentCategories.json`、`providerCredentials.json`、
     `app-update.json` + `media-center-app.apk`（从 workwork 服务器
     `.wework/generated/media_center/` 快照迁移）；
   - `data/vod/`：`youtube-cookies.txt`（从 `.wework/vod_extractor/` 迁移；
     YouTube 解析硬前提，过期需重新导出）。
5. **启动**：`cp .env.example .env && $EDITOR .env && make build && make up nginx media_server vod_extractor bgutil_pot`。
   `bgutil_pot` 是 YouTube PO Token provider，只走 backend 内网；`vod_extractor`
   内置 Deno + yt-dlp-ejs，并把内存上限设为 768M（签名求解需要）；`media_server`
   限制 64M 并设 `GOMEMLIMIT=48MiB`（限流表/缓存/现代 SQLite 的堆预算按此收缩），
   超时响应统一走 JSON 错误包络。
6. **验证**：`curl -s https://media.chaoset.com/healthz`（应回
   `{"status":"ok","version":"..."}`）；App 侧以新基址
   `https://media.chaoset.com/api/v1` 拉一次 providers。

## 版本统一

**`deploy/versions.env` 是本站全部版本键的唯一事实来源**（GO_VERSION /
ALPINE_VERSION / PYTHON_VERSION / NGINX_VERSION / YT_DLP_VERSION）。
升级：改 versions.env → `make build` → `make up <svc...>`。改完顺手核对
media_server/Dockerfile 与 `.github/workflows/ci.yml` 的同名字段仍一致
（compose 的 `:?` 守卫会在缺键时直接报错，绕过 Makefile 的裸 compose 调用
必须 `--env-file versions.env`）。

## 备份与监控

- **catalog 备份**（system of record，当前唯一持久业务数据）：`make backup`
  经 `/internal/catalog/export` 导出全量成员 JSON 到宿主 `backups/`
  （依赖 .env 配置 `MEDIA_SERVER_INTERNAL_TOKEN`；导出可用
  `/internal/catalog/import` 回放）。建议每日 cron + 异地留存；
  需要整机级快照时先 `docker compose stop media_server` 再复制
  `media_data` 卷（避免 SQLite WAL 半写状态）。
- **外部探活**：docker healthcheck 只影响容器状态，假死/重启循环无告警。
  在 Cloudflare 为 `https://media.chaoset.com/healthz` 配置 Health Check
  并接通知渠道。
- **cookies 失效告警**：vod_extractor `GET /health` 返回 `cookies` 字段
  （present/readable/age_seconds）与 `failures.counts`（按分类累计，风控为
  `bot_check`）。cookies 缺失/过期或 age 异常增大意味着 **YouTube 解析**已经瘫痪
  （`/extract` 返回 502 `bot_check`）；**搜索不受影响**——`/search` 走
  ytsearch flat 列表页，无 cookies 仍然可用（2026-10-12 线上实测）。
  告警口径：看 **`failures_window.counts`（近 5 分钟）** 的 `bot_check` 是否为非零/持续增长——累计 `failures.total` 只增不减，无法区分"仍在失败"与"已恢复"；`active`(in_flight/waiting) 可区分"槽位排队"与视频问题，`successes.last_at` 可确认恢复时间。
  可由外部监控定时打内网 /health，或在 media_server 侧观察 resolve 的 503 `RESOLVE_UNAVAILABLE` 率（基础设施类失败）与 `/healthz` 的 `youtube.circuit`。
- **cookies 会话自续期**：cookies 过期前无需浏览器重新导出——`make cookies-refresh` 用现有 cookies 以同款 extractor-args 完成一次真实解析，yt-dlp 把回发的新会话 token 原子写回 cookie 文件（保留原属主/权限，备份 `cookies.bak.*`）。仅当刷新解析本身报 `bot_check`（会话彻底失效）才需人工重新导出。

## 边缘缓存（可选）

media_server 对**版本化** APK 下载地址（`/api/v1/app/download?...&v=<sha256 前 12 位>`，
由 `/api/app/update` 下发的 `apkUrl` 自动携带）返回 `Cache-Control: public, max-age=31536000, immutable`；
缺省/不匹配 `v` 的旧地址仍 `no-store`。如需经 Cloudflare 缓存 APK 降低回源：建 Cache Rule
（URL 匹配 `/api/v1/app/download*` 且存在查询参数 `v`）→ Eligible for cache、Edge TTL 1 年。
**不要在无 `v` 的地址上开缓存**（发版后会缓存住旧 APK 导致更新校验失败）。
catalog JSON 接口已带 `Cache-Control: public, max-age=30/60`，可按需加 Cache Rule；
凭证（`/providers/credentials`）、youtube 实时端点、崩溃上报、内部接口带 `no-store` 或限流语义，禁止缓存。

## 运维

- **版本升级**：改 `versions.env` → `make build` → `make up <svc...>`。
  镜像 tag 为部署时 `git describe`（不可变，回滚 = `IMAGE_TAG=<旧tag> make up <svc...>`）。
- **分类/凭证/App 发版**：编辑 `data/generated/` 下对应文件后
  `make restart media_server`（凭证与 manifest 热加载，APK 换文件即可）。
- **App APK 自动化发布**：push `vX.Y.Z` tag 后由 GitHub Actions 构建并发布 GitHub Release；配置仓库变量 `ENABLE_AUTO_DEPLOY=true` 与部署 secrets（`DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_SSH_KEY` / `DEPLOY_KNOWN_HOSTS`）后会自动把 APK 与两份 manifest 写入 `data/generated/`（normal 与 legacy 各自只保留最近 5 个版本化 APK，无需重启）。配置见仓库根 AGENTS.md「APK 发版（GitHub Actions）」。
- **catalog 审校/导入**：`/internal/*` 不经公网（nginx 404），经宿主机调用：
  `docker exec media_center-media_server-1 wget -qO- --header "X-Internal-Token: $TOKEN" http://127.0.0.1:8787/internal/catalog/overview`。
- **过渡期别名**：`/api/*`（无 v1）仅供 workwork nginx 过渡反代与存量 App，
  客户端全量切换后删除 nginx 模板中的 `(?:/v1)?` 正则分支与 media_server 的
  `apiV1Prefix` 归一化（见仓库根 AGENTS.md）。
