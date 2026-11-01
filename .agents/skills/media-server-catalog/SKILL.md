---
name: media-server-catalog
description: media_server catalog 分类数据与审核体系。触发：改分类数据模型、候选导入与审核流程、分类浏览与搜索、youtube 实时通道时加载。
---

# media_server catalog 与内容审核

## 分类数据与审核流程

- **分类数据（catalog，system-of-record）**：SQLite，由 `catalog.go` 用纯 Go 的 `modernc.org/sqlite`（无需 cgo）落地维护；分类浏览只读该库的 `approved` 数据（见 `catalog_serve.go` 与 `catalog.go` 的 `SearchApproved`）；没有 `approved` 数据时返回空列表 / 空搜索结果。
- **内容分类**：`media_server/src/contentCategories.json`（仓库内默认样例）；线上由部署侧 `deploy/data/generated/contentCategories.json` 注入（编辑后 `make restart media_server`）。
- **候选不提交仓库**：候选（外部 / AI 收集汇总）是中间状态，不纳入版本管理；最终数据以 catalog.db（system-of-record）为准，可通过 `/internal/catalog/export` 导出（部署侧 `make backup` 一键导出到宿主 `backups/`，见 deploy/README.md「备份与监控」）。各分类的收集格式与审核标准见 `media_server/CATEGORY_CURATION_GUIDE.md`。
- **审核流程**：外部收集候选 → 导入 pending → 人工 + AI 审核 → 上线 approved。候选经 `/internal/catalog/import` 导入为 `pending`（重复导入只新增，绝不覆盖已有审核决定）；由人工 + AI 模型审核 approve / reject，可选 `pinned` / `rank`（经 `/internal/catalog/*`，也可用 `-catalog-review` 交互命令或任意 SQLite 工具）；客户端的分类浏览与搜索只命中该库 `approved` 清单（顺序 `pinned` > `rank` > `score`）。
- **审核是唯一的质量关口**：聚合视频未经审核普遍标题与内容不符、大量无法播放，相关性、可播放性等筛选一律交给 admin 的人工 + AI 审核。**youtube 实时搜索是这个原则的受控例外**：`/api/providers/youtube/search` 经 vod_extractor 用 yt-dlp ytsearch 实时搜索（无 Data API 配额），只为 App 即时展示/播放，结果不写 catalog、不进分类列表，审核关卡不被绕过。
- **youtube 分类**：`/providers/youtube/categories` 返回服务端内置 6 类（音乐/科技/游戏/纪录片/电影/学习）；`/providers/youtube/categories/{id}/videos` 把分类映射为一次实时 ytsearch（需 `X-App-Token`，与搜索共用 12/min/IP，仅第 1 页）。不新增 catalog 数据、不绕过审核。
- **只存原始数据**：catalog 只存上游原始字段（缩略图为原始地址）。

## 数据库与环境变量

- **数据库文件不提交仓库**：catalog 库写到 `MEDIA_SERVER_CATALOG_DB`（默认 `data/catalog.db`，含 `-wal`/`-shm` 旁文件，已 gitignore）；线上由部署侧 compose 的 `media_data` 卷持久化（`MEDIA_SERVER_CATALOG_DB=/app/data/catalog.db`，见 `deploy/docker-compose.yml`）。
- `MEDIA_SERVER_CATALOG_DB` 置空则关闭 catalog，分类浏览与搜索返回空。

## 浏览查询口径

- 分类顺序：`/providers/{p}/categories` 按 `contentCategories.json` 的编排顺序输出（运营可调），catalog 中未定义的分类排在末尾并标"未定义分类 <id>"。
- 分P过滤：`_p<数字>` 后缀只在 `provider='bilibili'` 时视为分P并隐藏/合并；其他 provider（如 YouTube 合法 ID `abcdefgh_p1`）原样可见。
- 时长筛选：分类视频端点支持 `minDurationSeconds` / `maxDurationSeconds`（秒，含边界），计数与列表共用同一 WHERE；非法值 400。启用筛选时 `duration_sec` 为空的视频被排除。
- 索引：`category_members` 上有 `(category_id,status)`、`(provider,category_id,status)`、`(provider,status)` 三个索引。

## YouTube 解析（App 播放链路）

- 1080P+ 常见形态是"纯视频 DASH 轨 + 纯音频轨"：`/providers/youtube/resolve` 的 option 会带 `audioUrl`/`audioMimeType`，App 端 `PlaybackMediaSourceFactory` 读 MediaItem extras 后用 `MergingMediaSource` 合流（MediaController 不能直接传 MediaSource）。
- 每档高度只留一条候选：H.264 > VP9 > AV1，其次帧率，再优先合一 progressive/HLS，最后码率；没有音轨的纯视频格式直接丢弃（不能返回无声视频）。
- resolve 结果进程内缓存（成功 15min / 失败 5min），同 videoID 在途请求用单飞合并；负缓存判定必须用 failure 标志，不能看 reason 是否为空。

## 合集与自动连播

- 合集表：`collections` + `collection_members`（provider/collection_id/native_id/position），只存分组关系，不新增视频、不改审核状态。
- 写入：`POST /internal/catalog/collections` 全量替换某 provider 的合集快照（内部令牌）；公开接口 `GET /providers/{p}/videos/{id}/collection` 只返回其中 `approved` 剧集，按合集列表顺序，`currentIndex=-1` 表示当前视频不在队列。
- 采集：`scripts/sync-bilibili-collections.py` 从 B 站 `view.ugc_season` 提取有序剧集；服务器 IP 被 B 站风控，脚本在可访问 B 站的开发机运行，经 SSH 隧道写内部接口。发现一个合集后会跳过其剧集，减少请求；`--dry-run/--output/--limit` 便于调试。
- 删除/替换：合集写入是事务内全量替换；`DeleteMember` 删除孤儿视频时同步清理 collection_members。
