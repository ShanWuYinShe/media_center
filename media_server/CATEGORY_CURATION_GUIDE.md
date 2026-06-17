# 分类视频审核标准（Category Curation Guide）

本文件是 **media_server 分类视频数据的唯一审核依据**，供 **admin 页面的人工审核** 与 **AI 模型辅助审核** 共同遵循。`catalog.go` 维护的 SQLite 库（`category_members.status`）是 system-of-record：分类浏览与搜索**只读 `approved` 行**，因此「能否上线」完全取决于本标准下的审核结论。

> 相关代码：数据模型与读写见 `catalog.go`；内部管理接口见 `internal_api.go`；分类浏览的实时读取见 `main.go`（`handleProviderCategoryVideos`）。

---

## 一、数据流（审核在流程中的位置）

```
外部收集/AI 汇总候选（导入信封 JSON）
        │  POST /internal/catalog/import  （admin 代理：/api/admin/media-center/catalog/import）
        ▼
  catalog.db: status = pending（待审；重复导入只新增候选，绝不覆盖已有审核决定）
        │  按本标准审核：POST /internal/catalog/review  → approve / reject / 置顶 / 排序
        ▼
  status = approved（上线）/ rejected（软拒绝，留痕）
        │
        ▼
   客户端实时读取：/api/providers/{provider}/categories/{categoryId}/videos 只命中 approved
```

**立即生效**：media_server 每次请求都直接 SQL 查询 catalog.db（`ApprovedPage` / `SearchApproved`），**无内存缓存、无快照中间层**。approve / import(approved) 后下一次客户端请求即可见，**无需重启 media_server**。这是上一轮移除 seed / snapshot 中间层后的设计结论。

**catalog.db 的写入途径仅限**（均经 admin `requireRole("admin")`）：
- `/internal/catalog/import`（导入候选，可一步 approved）
- `/internal/catalog/review`（审核变更）
- `/internal/catalog/delete`（硬删除，区别于 reject 的软标记）

不存在任何自动采集 / 实时聚合 / 定时同步路径。

---

## 二、通用审核原则（所有分类适用）

1. **可播放性**：视频须实际可播放——人工/AI 应剔除付费墙、地区限制、已下架、需登录的条目（聚合视频常因此不可用，这是设置审核关口的根本原因）。
2. **内容相符**：视频实际内容须与标题、所属分类一致；标题党、名不副实者 reject。
3. **单一归属**：一条视频只归**最契合的一个分类**。若已出现在其他分类，按「边界判定」决定保留处，另一处删除（避免跨分类重复）。
4. **形态合规**（通用排除项，无合适分类即 reject）：
   - **游戏录屏 / 攻略 / 流程解说**（即使基于影视 IP，如「蝙蝠侠游戏通关」）
   - **纯音频 / 音乐**（无画面）
   - **短视频拼接 / 合集刷量**
   - **广告 / 引流 / 推广**
   - **影视解说 / 混剪 / 「X 分钟看完」**（非原片，除非该分类明确收录解说类）
   - **动画 / 番剧**（当前无动画分类；如《猫和老鼠》等纯动画短剧集若无更合适分类，reject）
5. **元数据完整**：标题必填；description / durationSeconds / thumbnailUrl 应尽量补全（搜索依赖 title + description + creators 匹配，缺失会降低可发现性）。
6. **留痕优于硬删**：对「不符合本分类但本身合规」的条目，优先 **reject**（保留可追溯的拒绝记录）；仅对违规 / 重复 / 垃圾条目用 **delete** 硬删除。审核历史应有据可查，避免再次导入同一条目时无从判断。

> 历史数据教训：线上库当前 `rejected = 0`，所有不合格条目都被 delete 而非 reject，导致无法区分「未审核」与「审核拒绝」。**后续审核应多用 reject 留痕。**

---

## 三、各分类审核标准

### movies · 电影

- **定义**：单片完整的电影故事片（含戏曲电影、纪录电影），单部时长通常 ≥ 60 分钟。
- **纳入**：国内外院线片、经典老电影、戏曲电影（如《霸王别姬》京剧版）、单片纪录电影。
- **排除**：
  - 游戏流程 / 攻略（即使基于电影 IP）— 反例：`BV1Ls4y1x7C4`《勇敢的心》游戏流程、`BV1pz421a7EQ》蝙蝠侠游戏攻略、`BV1Db411W7Zg》怪兽电力公司 GBA
  - 电视剧 / 多集内容（→ tv-series）
  - 影视解说混剪（通用排除）
- **边界判定**：
  - 革命 / 战争 / 历史题材的老电影 → **归 red-classic**，不归 movies（如《地道战》《红孩子》《小二黑结婚》）
  - 单部戏曲电影可归 movies；连续戏曲剧集归 tv-series
- **正例**：《肖申克的救赎》、《控方证人》1957、《霸王别姬》（京剧）

### tv-series · 电视剧

- **定义**：多集叙事的电视剧 / 连续剧——国产剧、海外剧、古装、武侠、谍战、军旅等。
- **纳入**：多集叙事剧集（有「第 N 集 / EP / 全集 / 完结」等形态标志）。
- **排除**：
  - **自然 / 美食 / 历史纪录片**（当前无纪录片分类，**直接 reject**）— 反例：`BV1bV4y1T7S4》企鹅群里有特务、`BV1x8411G7Ni》蓝色星球、`BV1hN411N7Cj》人类星球、`BV1gB4y1S7rR》街头美食斗士
  - 单片电影（→ movies）
  - 游戏内容（通用排除）
- **边界判定**：
  - 家庭 / 伦理 / 生活题材的剧集 → **归 family-theater**（见下）
  - 革命战争题材剧集 → 视主体归 red-classic
- **正例**：《老友记》、《是，首相》、《是，大臣》类英剧/国产剧

### family-theater · 家庭剧场（**需收紧，当前污染最严重**）

- **定义**：**以家庭生活、亲情、婚姻、代际关系为核心题材**的剧集——都市家庭、农村生活、婆媳、年代家庭。
- **纳入**：家庭伦理剧、生活剧、年代家庭剧（如《父母爱情》《乡村爱情》《情满四合院》《金婚》《都挺好》类）。
- **排除**（**这是本分类的重点整改方向**）：
  - **非家庭主题的普通电视剧** → 归 tv-series 或 reject。反例：`BV1pFWxzQEM5》漫长的季节（悬疑罪案）、`BV1ZVGvzCEo3》初来乍到（美剧）、`BV1Vz4y1b7XV》坏妈妈（韩剧）、各类法剧 / 欧美剧
  - 影视解说 / 微剧场（如《红楼梦》微剧场 `BV1CC4y1a7ee》）→ 通用排除
  - 标题无信息的占位条目（如仅「第六季」「第三季」`BV18pcwzuEKs》）→ reject 或补全后再审
- **判定要点**：**核心冲突是否围绕家庭 / 婚姻 / 亲情 / 代际**。是 → family-theater；否（即便是生活背景）→ tv-series。
- **正例**：《父母爱情》《乡村爱情》《情满四合院》《金婚》

### red-classic · 红色经典

- **定义**：**中国革命、战争、历史题材**（时间跨度约辛亥革命至改革开放前）的影视作品——含相关老电影、纪录片、剧集。
- **纳入**：长征、抗战、解放战争、抗美援朝、对越自卫反击等题材；《地道战》《地雷战》《上甘岭》《英雄儿女》《闪闪的红星》《小兵张嘎》《智取威虎山》等经典；相关历史纪录片 / 沙盘推演。
- **排除**：
  - **非革命题材的普通老电影** → 归 movies
  - **外国战争片** → 归 movies 或 reject
- **边界判定**：
  - 革命 / 战争题材的老电影（即使是故事片形态）→ **归 red-classic**，不归 movies。正例（曾误判为 movies，实属本类）：《海鹰》1959、`BV1CPpwetEpQ》、`BV14U4y1N7B6》红孩子 1958、`BV1FR4y1u7FH》小二黑结婚、《芙蓉镇》
- **正例**：《长征》、《松骨峰阻击战》、解放战争系列纪录片

### dota-zone · Dota 专区

- **定义**：Dota1 / Dota2 的对局、录像、解说、精彩集锦。
- **纳入**：完整对局、比赛录像、解说视频、集锦、主播路人局。
- **排除**：其他游戏、非游戏内容。
- **现状**：本分类数据质量最优（97% 符合、0 错分），维持现有标准即可。
- **正例**：DOTA 大神对局、Burning世界第一C、AniMajor 冠军记

---

## 四、候选收集与导入格式

候选由 **外部工具 / AI 模型 / 人工** 收集合规公开视频后，按导入信封格式汇总，**不再提交进仓库**（候选是中间状态，最终数据以 catalog.db 为准，可通过导出接口获取）。

### 导入信封

顶层固定为 JSON 对象（**不要用顶层数组**），与 `POST /internal/catalog/import` 一致：

```json
{
  "reviewer": "可选，admin 导入时自动覆盖为登录用户名",
  "items": [
    {
      "categoryId": "movies",
      "provider": "bilibili",
      "nativeId": "BV1...",
      "title": "标题（必填）",
      "description": "简介（建议补全，影响搜索）",
      "creators": ["作者"],
      "durationSeconds": 7311,
      "year": "1957",
      "thumbnailUrl": "https://...",
      "sourceUrl": "https://www.bilibili.com/video/BV1...",
      "score": 90,
      "status": "pending"
    }
  ]
}
```

### 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `categoryId` | 是 | 分类 ID（须为已启用分类） |
| `provider` | 是 | 来源（当前 `bilibili`） |
| `nativeId` | 是 | 来源站稳定 ID（B 站 BV 号） |
| `title` | 是 | 标题 |
| `description` / `creators` / `durationSeconds` / `year` / `thumbnailUrl` / `sourceUrl` | 否 | 元数据，建议尽量补全 |
| `score` | 否 | 人工/AI 给的排序参考分，**仅用于展示排序，不参与任何自动筛选** |
| `status` | 否 | `pending`（默认，待审）或 `approved`（一步上线，留 reviewer 痕迹） |
| `pinned` / `rank` | 否 | 展示次序控制，主要用于 export→import 忠实回放 |

### 导入语义（重要）

- **重复导入只新增候选，绝不覆盖已有审核决定**：已存在的 `(categoryId, provider, nativeId)` 成员保持原 status；`ON CONFLICT DO NOTHING`。
- 要**改既有成员状态**（如 pending → approved / rejected），走审核接口 `review`，不要重新 import。
- 单次最多导入 1000 条。

---

## 五、审核操作入口

| 操作 | admin 页面 | admin API（代理） | 内部接口 |
|------|-----------|-------------------|----------|
| 概览计数 | 分类审核管理 | `GET /api/admin/media-center/catalog/overview` | `GET /internal/catalog/overview` |
| 列出成员 | 分类审核管理 | `GET /api/admin/media-center/catalog/items` | `GET /internal/catalog/items` |
| 审核 approve/reject/置顶/排序 | 逐条操作 | `POST /api/admin/media-center/catalog/review` | `POST /internal/catalog/review` |
| 硬删除 | 删除按钮 | `POST /api/admin/media-center/catalog/delete` | `POST /internal/catalog/delete` |
| 导入候选 | 导入视频集 | `POST /api/admin/media-center/catalog/import` | `POST /internal/catalog/import` |
| 导出 | 导出按钮 | `GET /api/admin/media-center/catalog/export` | `GET /internal/catalog/export` |

- 命令行备用审核入口：`media_server -catalog-review`（交互式逐条 approve/reject，需访问 catalog.db）。
- 审核排序：`pinned`（置顶）> `rank`（人工序）> `score` > `native_id`。

---

## 六、运维：备份与新环境初始化

- **catalog.db 是唯一数据源**，不提交进仓库（`.gitignore` 排除 `*.db` / `*.db-wal` / `*.db-shm`），位于容器命名卷 `media_center_data`（容器内 `/app/data/catalog.db`）。
- **定期备份**：通过导出接口拉取全量 JSON 存档——
  `GET /internal/catalog/export`（不带参数 = 全部分类、全部状态）。
  导出的 JSON 与导入信封兼容，可直接 `POST /internal/catalog/import` 回灌。
- **新环境初始化**：从现有环境 export 全量 → 在新环境 import（approved 项一步上线）。
- **环境变量**：`MEDIA_SERVER_CATALOG_DB`（默认 `data/catalog.db`，置空则关闭 catalog，分类浏览与搜索返回空）。
