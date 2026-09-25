---
name: media-server-catalog
description: media_server catalog 分类数据与审核体系。触发：改分类数据模型、候选导入与审核流程、分类浏览与搜索、youtube 实时通道时加载。
---

# media_server catalog 与内容审核

## catalog 与内容审核

- 内容分类：`src/contentCategories.json`（仓库内默认样例）；线上由部署侧 `deploy/data/generated/contentCategories.json` 注入（编辑后 `make restart media_server`）。
- 分类数据（catalog，SQLite，system-of-record）：分类视频列表由 `catalog.go` 用纯 Go 的 `modernc.org/sqlite`（无需 cgo）落地维护库；分类浏览只读该库的 `approved` 数据（见 `catalog_serve.go` 与 `catalog.go` 的 `SearchApproved`）；没有 `approved` 数据时返回空列表 / 空搜索结果。
  - 候选不提交仓库：候选（外部 / AI 收集汇总）是中间状态，不纳入版本管理；最终数据以 catalog.db（system-of-record）为准，可通过 `/internal/catalog/export` 导出（部署侧 `make backup` 一键导出到宿主 `backups/`，见 deploy/README.md「备份与监控」）。各分类的收集格式与审核标准见 `CATEGORY_CURATION_GUIDE.md`。
  - 流程：外部收集候选 → 导入 pending → 人工 + AI 审核 → 上线 approved。候选经 `/internal/catalog/import` 导入为 `pending`（重复导入只新增，绝不覆盖已有审核决定）；由人工 + AI 模型审核 approve / reject，可选 `pinned` / `rank`（经 `/internal/catalog/*`，也可用 `-catalog-review` 交互命令或任意 SQLite 工具）；客户端的分类浏览与搜索只命中该库 `approved` 清单（顺序 `pinned` > `rank` > `score`）。
  - 审核是唯一的质量关口：聚合视频未经审核普遍标题与内容不符、大量无法播放，相关性、可播放性等筛选一律交给 admin 的人工 + AI 审核。**youtube 实时搜索是这个原则的受控例外**：`/api/providers/youtube/search` 经 vod_extractor 用 yt-dlp ytsearch 实时搜索（无 Data API 配额），只为 App 即时展示/播放，结果不写 catalog、不进分类列表，审核关卡不被绕过。
- 只存原始数据：catalog 只存上游原始字段(缩略图为原始地址)。
- 数据库文件不提交:catalog 库写到 `MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,含 `-wal`/`-shm` 旁文件,已 gitignore);线上由 `workwork` 挂卷生成。
  - 相关环境变量:`MEDIA_SERVER_CATALOG_DB`(默认 `data/catalog.db`,置空则关闭 catalog,分类浏览与搜索返回空)。

