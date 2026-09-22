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
   `~/work/mac-migration-backup/cf_token_and_cert/`）。
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
5. **启动**：`cp .env.example .env && $EDITOR .env && make build && make up nginx media_server vod_extractor`。
6. **验证**：`curl -s https://media.chaoset.com/healthz`（应回
   `{"status":"ok","version":"..."}`）；App 侧以新基址
   `https://media.chaoset.com/api/v1` 拉一次 providers。

## 版本统一

基础镜像版本由 workwork 仓库的 `versions.env` 作主本统一管理（本仓
`deploy/versions.env` 是其在媒体站的落地，含站点专属键 PYTHON_VERSION）。
跨仓校验/同步工具：workwork 仓 `scripts/sync-versions.sh check|sync`；
勿单方面修改本仓版本值。

## 运维

- **版本升级**：改 `versions.env` → `make build` → `make up <svc...>`。
  镜像 tag 为部署时 `git describe`（不可变，回滚 = `IMAGE_TAG=<旧tag> make up <svc...>`）。
- **分类/凭证/App 发版**：编辑 `data/generated/` 下对应文件后
  `make restart media_server`（凭证与 manifest 热加载，APK 换文件即可）。
- **catalog 审校/导入**：`/internal/*` 不经公网（nginx 404），经宿主机调用：
  `docker exec media_center-media_server-1 wget -qO- --header "X-Internal-Token: $TOKEN" http://127.0.0.1:8787/internal/catalog/overview`。
- **过渡期别名**：`/api/*`（无 v1）仅供 workwork nginx 过渡反代与存量 App，
  客户端全量切换后删除 nginx 模板中的 `(?:/v1)?` 正则分支与 media_server 的
  `apiV1Prefix` 归一化（见仓库根 AGENTS.md）。
