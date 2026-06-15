# 分类内容判定与维护指南

本文档说明**如何判断一个视频是否可以加入某个分类**，以及推荐的**分类数据存储与维护方案**。
目标是让外部工具（脚本 / 后台 / 人工 / AI）能按统一标准维护 `media_server` 的分类数据，
保证结果精确、稳定、可审计。

判定逻辑与 `media_server/main.go` 的 `relevanceScore` 完全一致；分类的 `keywords` / `excludes` /
`searchQueries` / `minDurationSeconds` 以 `media_server/src/contentCategories.json` 为准。
本文档与代码冲突时，**以代码为准**，并请同步更新本文档。

---

## 1. 存储选型：JSON 还是 SQLite？

先区分两层，别混为一谈：

| 层 | 作用 | 数据量 | 访问方式 | 推荐格式 |
|----|------|--------|----------|----------|
| **运行时快照** | 服务端按分类秒回给 App 的成品列表 | 小（每类 ~60，5~30 类共几 MB） | 启动load进内存，请求从内存切片 | **JSON（保持现状）** |
| **维护数据库** | 外部工具长期维护的“候选 + 审校决定”目录 | 可大（上万条 + 元数据 + 审校留痕） | 工具增删改查、并发读写、复杂筛选 | **SQLite（推荐）** |

**结论：**

- **运行时快照继续用 JSON 就够了，不是瓶颈。** 服务端把快照一次性load进内存（`SnapshotStore`），
  每个请求只是对内存切片分页，**不会**每次解析 JSON。即便涨到 30 个分类 × 每类 300 条
  （~9MB），启动解析也就几十毫秒，请求仍是亚毫秒级。JSON 还有“人类可读、可 git diff、好调试”
  的优点。换成 SQLite 对**serving 性能没有收益**。

- **你要做的“用其他工具专门维护分类数据”这层，SQLite 明显更合适。** 一旦超出简单的
  屏蔽/置顶清单，你会需要：每条视频的审校状态、来源出处、人工评分、修改留痕、跨分类去重、
  “待审 / 最近 7 天新增”这类查询、以及多个工具并发安全读写。这些用一堆 JSON 文件做会很别扭，
  SQLite（开 WAL 模式）天然胜任。

**推荐架构（职责分离）：**

```
        ┌──────────────────────┐   导出/物化   ┌──────────────────────┐
外部工具 │ 维护数据库 curation.db │ ───────────▶ │ 运行时快照 *.json      │ ──▶ App
(增删改) │ (SQLite, system of    │              │ (media_server 内存serving)│
        │  record + 审校状态)    │              └──────────────────────┘
        └──────────────────────┘
```

- **System of record = SQLite**：工具读写的唯一真相源，一行一个“(分类, 视频)”及其元数据 + 审校状态。
- **运行时产物 = JSON 快照**：从“已通过(approved)”的行物化出来，服务端只serving、不依赖 DB，保持简单快。
- **桥接**：一个构建步骤把 approved 行导出成 JSON 快照（或导出成 `curation.json` 的 block/pin 列表）。

> 服务端已具备物化所需积木：`summaryByID`（按原生 ID 拉取摘要）、`SnapshotStore`（落盘 + serving）。
> 若想做“纯人工固定清单”，让构建步骤读 approved 列表、按 `native_id` 拉详情、写进快照即可。

第 7 节给出可直接采用的 SQLite 表结构。

---

## 2. 视频 ID 约定（重要）

引用视频时**优先用稳定的原生 ID**，不要用接口返回的公开签名 id。

| 写法 | 例子 | 稳定性 | 适用 |
|------|------|--------|------|
| `provider:nativeID`（**推荐**） | `bilibili:BV1xxx` | 稳定，不随签名密钥变化 | 维护数据库、curation.json |
| 裸原生 ID | `BV1xxx` | 稳定（默认按 bilibili 解析） | 同上 |
| 公开签名 id | `v1.<base64>.<sig>` | **不稳定**（换签名密钥即失效） | 仅临时、即时消费 |

- bilibili 的原生 ID 就是视频页 URL 里的 **BV 号**（`https://www.bilibili.com/video/BV1xxx`）。
- 审校文件 `curation.json` 的 `blockIds` / `pinIds` 三种写法都认；服务端会把列表项的公开 id
  解码成 `provider:nativeID` 再比对，所以你**只需记 BV 号**即可长期稳定地屏蔽/置顶。

---

## 3. 通用准入规则（所有分类先过这关）

按顺序判断，任一条不满足即**拒绝**：

1. **必须是点播视频**：`playbackKind == "vod"`。直播（live）一律不收。
2. **判定文本**：把 `title + description + creators` 拼接后转小写，记作 `text`，后续匹配都基于它。
3. **不得命中该分类的 `excludes`**：`text` 含任一该分类 exclude 词 → 拒绝。
   （解说/讲解/盘点/速看/影评/混剪/分钟看/一口气看/吐槽/预告/花絮/片段/剪辑/名场面/合集… 见各分类配置。）
4. **不得命中成人/色情黑名单**（跨分类，合规硬性）：`text` 含任一以下词 → 拒绝。
   ```
   三级片 三級片 情色 色情 成人电影 成人影片 限制级 限制級 18禁 r18 里番 裏番
   无码 無碼 黄片 黄色电影 伦理片 倫理片 性爱 性愛 激情戏 激情戲 床戏 床戲 福利姬 擦边 擦邊
   ```
5. **时长门槛**：若已知时长（`durationSeconds > 0`）且小于门槛 → 拒绝。
   - `dota-zone`：**180 秒**（允许集锦/解说）。
   - 其余分类：**1200 秒（20 分钟）**。
   - 时长未知（`0`）时**不**因时长拒绝（交给后续主题/关键词判断）。
6. **关键词命中分（keywordScore）**：遍历该分类 `keywords`，`text` 命中第 `index` 个词就加
   `max(1, len(keywords) - index)` 分。即**靠前的关键词权重更高**。该分多用于排序，也是多数分类的准入门槛。

---

## 4. 各分类判定标准

下表“附加门槛”在通用规则之上叠加。除 `dota-zone` 外，均要求 `keywordScore > 0`。

### 4.1 电影 `movies`
- **附加门槛**：`keywordScore > 0` **且** `text` 含 `完整版` / `全片` / `正片` 之一。
- **要点**：必须是一部完整长片（≥20 分钟），不是解说/合集/连续剧/短剧。
- **排除**（节选，详见配置）：解说、讲解、盘点、速看、影评、混剪、分钟看、一口气看、预告、花絮、
  片段、剪辑、合集、推荐、TOP10、韩剧/美剧/日剧/连续剧/全集（这些归电视剧）、网盘/资源/打包。
- **合规**：过滤色情/三级（通用规则 4），适合全家观看。
- ✅ 通过：`《XXX》完整版 高清国语 院线电影`（90 分钟）。
- ❌ 拒绝：`十分钟看完XXX`（命中“分钟看/看完”）、`XXX电影解说`（命中“解说”）、`XX电视剧全集`（命中“全集”）。

### 4.2 电视剧 `tv-series`
- **附加门槛**：`keywordScore > 0` **且** 命中“剧集信号”之一：
  分集 regex `第N集 / N-M集 / EPN`，或含 `全集` / `连续剧` / `剧集`。
- **要点**：成套剧集（整部/分集/全集），不是单集电影、解说、短剧。
- **排除**（节选）：解说、混剪、速看、预告、花絮、**短剧**、网盘、打包、漫剧、reaction。
- ✅ 通过：`XXX电视剧 第01集`、`XXX 全集 1-40集`。
- ❌ 拒绝：`XXX短剧 全集`（命中“短剧”）、`XXX解说`（命中“解说”）。

### 4.3 家庭剧场 `family-theater`
- **附加门槛**：`keywordScore > 0` **且** 是剧集（分集 regex / 全集 / 连续剧 / 剧集 / 电视剧）
  **且** 命中家庭/生活主题词之一：
  ```
  家庭 都市 生活 农村 乡村 家长里短 婆媳 情感 年代 婚姻 亲情 母女 父子 邻里 市井
  家长 夫妻 婆婆 媳妇 父母 一家人 一家 全家 老伴 老两口 儿女 闺女 妯娌 街坊 邻居
  胡同 四合院 大杂院 大院 过日子 柴米油盐 烟火 离婚 相亲 家务 赡养 抚养 鸡毛蒜皮
  父母爱情 金婚 情满四合院
  ```
- **要点**：都市/农村家庭生活剧，家长里短、柴米油盐的烟火日常。
- **排除**（在电视剧基础上额外）：AI剧、穿越、重生、逆袭、战神、赘婿、(霸道)总裁、神医、兵王、龙王、闪婚、替嫁
  （这些是爽剧/短剧噪声）。
- ✅ 通过：`父母爱情 电视剧全集`、`情满四合院 连续剧`、`东北一家人 全集`。
- ❌ 拒绝：`霸道总裁爱上我 全集`（命中“总裁”）、`穿越之XXX`（命中“穿越”）、单集电影。

### 4.4 红色经典 `red-classic`
- **附加门槛**：`keywordScore > 0` **且** 命中“**中国相关**革命/战争/历史”主题词之一：
  ```
  红色 革命 辛亥革命 辛亥 北伐 长征 抗战 抗日 解放战争 解放军 红军 八路军 新四军 游击
  抗美援朝 志愿军 对越 自卫反击 珍宝岛 中苏 对印 建党 建国 开国大典 渡江 淮海 辽沈 平津
  南昌起义 井冈山 延安 西路军 上甘岭 英雄儿女 地道战 地雷战 闪闪的红星 铁道游击队 董存瑞
  狼牙山 小兵张嘎 南征北战 红色娘子军 智取威虎山 林海雪原 平原游击队 渡江侦察记 永不消逝的电波
  ```
- **要点**：辛亥革命至今、与中国相关的革命/战争/历史题材，偏纪录片与历史正片。
  **刻意不含“历史/纪录片/战争”这类泛词**，避免把外国历史（如二战德国）放进来。
- **排除**（节选）：解说、混剪、歌曲、MV、伴奏、演唱、合唱、朗诵、舞蹈、广场舞、演讲、演唱会、
  插曲/片头曲/片尾曲/主题曲、游戏/实况/通关/攻略、合集。
- ✅ 通过：`抗日战争纪录片`、`长征 历史纪录片`、`上甘岭 战争片`、`辛亥革命 纪录片`。
- ❌ 拒绝：`二战德国闪电战纪录片`（未命中中国相关主题词）、`抗战歌曲合集`（命中“歌曲/合集”）。

### 4.5 Dota 专区 `dota-zone`
- **判定（与众不同，不要求 keywordScore>0）**：
  - `text` 含 `dota1` / `dota 1` / `魔兽争霸` / `allstars` → 强相关，`score = keywordScore + 30`。
  - 否则 `text` 含 `dota` / `dota2` / `ti` → 相关，`score = keywordScore + 5`。
  - 都不含 → **拒绝**（即便命中了“录像/解说/集锦”等通用词）。
- **时长门槛低**：≥180 秒，收录集锦/解说/路人局。
- **排除**：教学、教程、攻略、新手、入门、出装、改动、版本更新、补丁、鬼畜、音乐、音效、MV、WTF、广告。
- ✅ 通过：`DOTA1 路人局录像`、`DotA Allstars 经典对局`、`Dota2 TI 比赛`、`魔兽争霸3 DOTA`。
- ❌ 拒绝：`英雄联盟精彩集锦`（无 dota 词）、`DOTA 出装教学`（命中“教学/出装”）。

---

## 5. 打分与排序

- 最终 `score` 越高排越前；同分按聚合时的原始顺序稳定排列。
- 除 dota（+30 / +5）外，`score == keywordScore`。
- **想让某视频更靠前**：让标题/简介命中更多、且更靠前的 `keywords`；或在审校里 **pin 置顶**（见第 6 节）。
- 时长未知（0）不因时长被拒，但也拿不到时长加分——尽量补全 `durationSeconds`。

---

## 6. 审校文件 `curation.json`

提交进仓库、可手工编辑，构建快照时套用，用来**永久剔除噪声**和**强制置顶精品**。
位置：默认 `media_server/src/curation.json`（可用 `MEDIA_SERVER_CURATION_FILE` 覆盖）。

```json
{
  "categories": {
    "movies": {
      "blockIds":           ["bilibili:BV1xxxx"],
      "blockTitleContains": ["预告", "解说"],
      "pinIds":             ["bilibili:BV1yyyy"]
    }
  }
}
```

- `blockIds`：按视频 ID 永久剔除。**推荐用 `provider:nativeID` 或裸 BV 号**（稳定）。
- `blockTitleContains`：按标题/简介关键词剔除（小写包含匹配），适合批量去一类噪声。
- `pinIds`：强制置顶并按给定顺序排在最前；若该视频不在聚合结果里，会**按原生 ID 主动拉取收录**。
- 三个字段都可留空数组。审校是可选层，缺文件不影响主流程。

---

## 7. 推荐的维护数据库（SQLite）

外部工具用它做 system of record。开 `PRAGMA journal_mode=WAL;` 以支持并发读 + 单写。

```sql
-- 视频候选 + 抓取到的元数据(一条视频一行,跨分类共享)
CREATE TABLE videos (
  provider      TEXT NOT NULL,                 -- 'bilibili'
  native_id     TEXT NOT NULL,                 -- 'BV1xxx'(稳定原生 ID)
  title         TEXT NOT NULL,
  description   TEXT,
  creators      TEXT,                          -- JSON 数组字符串
  duration_sec  INTEGER,
  year          TEXT,
  thumbnail_url TEXT,                          -- 原始地址,仅供审校预览(勿当签名地址用)
  source_url    TEXT,                          -- 原始页面,便于人工核对
  fetched_at    TEXT,                          -- ISO8601
  PRIMARY KEY (provider, native_id)
);

-- 分类成员 + 审校决定(多对多:一个视频可进多个分类)
CREATE TABLE category_members (
  category_id   TEXT NOT NULL,                 -- 'movies' / 'red-classic' ...
  provider      TEXT NOT NULL,
  native_id     TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'pending', -- pending / approved / rejected
  pinned        INTEGER NOT NULL DEFAULT 0,      -- 是否置顶
  rank          INTEGER,                         -- 人工排序(可空,越小越前)
  score         INTEGER,                         -- 规则打分(第 5 节,参考用)
  reason        TEXT,                            -- 通过/拒绝理由(留痕)
  reviewer      TEXT,                            -- 谁审的(人或工具名)
  reviewed_at   TEXT,
  PRIMARY KEY (category_id, provider, native_id),
  FOREIGN KEY (provider, native_id) REFERENCES videos(provider, native_id)
);

CREATE INDEX idx_members_cat_status ON category_members(category_id, status);
```

常用查询：

```sql
-- 某分类已通过、按置顶/人工序/分数出列(用于物化成快照)
SELECT v.* FROM category_members m JOIN videos v
  ON v.provider=m.provider AND v.native_id=m.native_id
WHERE m.category_id='red-classic' AND m.status='approved'
ORDER BY m.pinned DESC, m.rank ASC NULLS LAST, m.score DESC
LIMIT 60;

-- 待审清单
SELECT * FROM category_members WHERE status='pending' ORDER BY score DESC;
```

**物化到运行时（已内置实现，见第 9 节）：**
- media_server 已内置“目录优先(catalog-first)”物化：构建快照时,某分类在库中有 `approved` 行就
  直接用库里的固定清单(顺序由库决定),否则回退实时聚合 + curation.json,与未接入库前行为一致。
- curation.json 仍可叠加:目录物化出的固定清单上,只会再套用 `blockIds`/`blockTitleContains`
  把临时发现的噪声剔掉(不重排);`pinIds` 的置顶语义由库的 `pinned`/`rank` 表达。

---

## 8. 判定流程（可直接照着实现）

外部工具对每个候选视频执行：

```
1. 规整：provider + native_id（BV 号）、title、description、creators、duration_sec。
2. playbackKind 必须是 vod，否则 reject。
3. text = lower(title + " " + description + " " + creators)。
4. 若 text 命中该分类 excludes 或成人黑名单 → reject（记 reason）。
5. 若 duration_sec>0 且 < 门槛(dota=180, 其余=1200) → reject。
6. 算 keywordScore（第 3 节第 6 条）。
7. 按分类附加门槛判定（第 4 节）：
     movies        : keywordScore>0 且 含 完整版/全片/正片
     tv-series     : keywordScore>0 且 含 分集regex/全集/连续剧/剧集
     family-theater: keywordScore>0 且 是剧集 且 含家庭主题词
     red-classic   : keywordScore>0 且 含中国革命/战争/历史主题词
     dota-zone     : 含 dota1/魔兽争霸/allstars→+30；或 dota/dota2/ti→+5；否则 reject
     其它          : keywordScore>0
   不满足 → reject。
8. 满足 → approved，记 score；可选 pinned/rank 调整次序。
9. 写入 category_members；构建步骤把 approved 物化进运行时快照。
```

> 把第 2~7 步实现成一个纯函数，与服务端 `relevanceScore` 行为对齐，就能保证“工具判定”和
> “服务端判定”一致；服务端代码更新时，请同步本文档与工具。

---

## 9. 服务端已内置的目录(catalog)命令与流程

第 7 节的 SQLite 结构已在 `media_server/catalog.go` 中落地，并接入构建流程。无需另写服务端代码，
按下面三步即可“本地先搜好 → 审校 → 服务端只发审过的固定清单”。

### 9.1 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MEDIA_SERVER_CATALOG_DB` | `data/catalog.db` | 分类维护数据库路径(SQLite, system-of-record)。打开失败时服务自动降级为实时聚合,不影响可用性。置空可彻底关闭。 |

数据库文件(含 `-wal`/`-shm` 旁文件)已被 `.gitignore` 忽略,不会误提交。schema 用
`CREATE TABLE IF NOT EXISTS` 兜底,首次打开自动建表,外部工具也可直接按第 7 节结构读写。

### 9.2 步骤一：灌入候选(seed)

把各分类的实时聚合结果作为 `pending` 候选写入库,供审校。重复运行只**新增**候选,
绝不覆盖已有的 `approved`/`rejected` 决定:

```bash
cd media_server
MEDIA_SERVER_CATALOG_DB=data/catalog.db go run . -catalog-seed
# 日志示例:catalog seed: category=movies candidates=120 new=120 pending=120 approved=0
```

### 9.2b 外部 AI / 工具收集结果导入待审

如果候选不是由 `media_server` 实时搜索产生，而是你让其他 AI 或脚本按本指南收集好的固定清单，
不要直接写运行时快照。推荐交付一个 JSON，然后通过 admin 代理导入 catalog。每条候选可选带
`status` 字段：

- 省略或 `"pending"`：进入 `pending` 待审队列，仍由 admin 按 9.3 做最后审查（默认，向后兼容）。
- `"approved"`：**你已自行审核过的视频集**，导入即收录上线（catalog-first 直读，approved 行立即
  对客户端可见，无需再 rebuild）。admin 会自动注入登录用户名为 `reviewer` 留痕，你 body 里传的
  `reviewer` 会被覆盖。

```json
{
  "items": [
    {
      "categoryId": "movies",
      "provider": "bilibili",
      "nativeId": "BV1xxxx",
      "title": "示例电影 完整版 正片",
      "description": "简介，可空",
      "creators": ["公开视频频道"],
      "durationSeconds": 5400,
      "year": "1984",
      "thumbnailUrl": "https://i0.hdslb.com/...",
      "sourceUrl": "https://www.bilibili.com/video/BV1xxxx",
      "score": 42,
      "status": "approved"
    }
  ]
}
```

导入方式（两种等价：admin Web UI「导入视频集」面板粘贴 JSON，或 curl 走 admin 代理）：

```bash
curl -X POST https://yinghuoren.com/api/admin/media-center/catalog/import \
  -H 'Content-Type: application/json' \
  --data-binary @collected-videos.json
```

返回 `{"videos":N,"imported":N,"approved":N,"skipped":N}`：`imported` 新增成员数，`approved`
其中直接上线数，`skipped` 已存在跳过数。

要求与语义：

- `categoryId` 必须是现有分类；`provider/nativeId/title` 必填。Bilibili 用稳定 BV 号。
- `status` 只接受空 / `pending` / `approved`，其它值整批 400 拒绝。
- 单次最多 1000 条；重复导入会刷新 `videos` 元数据，但**不会覆盖**既有 `approved/rejected` 审校决定
  （`category_members` 用 `ON CONFLICT DO NOTHING`，`status=approved` 只对**新成员**生效；要改既有
  成员状态请走 9.3 review）。
- 用 `status:"approved"` 导入的视频集会即时上线，无需 9.4 rebuild（catalog-first 架构下 rebuild 是
  no-op 确认）。用默认 `pending` 导入的仍按 9.3 审查。
- 外部 AI 应在 `score` 或自己的交付说明中保留判定理由；最终是否通过以 admin 审查为准。

### 9.3 步骤二：审校(approve / reject)

**首选内置 `-catalog-review` 子命令**(无需手写 SQL),两种模式:

```bash
cd media_server

# 交互逐条审校:展示 标题/UP/时长/简介/原始观看链接/分数,按 [a]pprove / [r]eject / [s]kip / [q]uit
MEDIA_SERVER_CATALOG_DB=data/catalog.db go run . -catalog-review
MEDIA_SERVER_CATALOG_DB=data/catalog.db go run . -catalog-review -catalog-review-category movies   # 只审某分类

# 非交互:按分数自动 approve 前 N 条(reject 留人工——进库候选都已过 relevanceScore 规则)
MEDIA_SERVER_CATALOG_DB=data/catalog.db go run . -catalog-review -catalog-review-auto -catalog-review-top 30
MEDIA_SERVER_CATALOG_DB=data/catalog.db go run . -catalog-review -catalog-review-auto -catalog-review-top 30 -catalog-review-dry-run  # 先预览不写库
```

`-catalog-review-auto` 只 approve、不自动 reject;想精细调序、批量拒绝或翻案已审项,
也可用任意 SQLite 工具(或你自己的外部工具)按第 4~5 节标准把候选改成 `approved`/`rejected`,
可选地设 `pinned`/`rank` 调序:

```sql
-- 看某分类待审清单(分数从高到低)
SELECT m.native_id, m.score, v.title, v.source_url
FROM category_members m JOIN videos v
  ON v.provider=m.provider AND v.native_id=m.native_id
WHERE m.category_id='movies' AND m.status='pending'
ORDER BY m.score DESC;

-- 通过 / 拒绝(记录审校人留痕)
UPDATE category_members SET status='approved', reviewer='me', reviewed_at=datetime('now')
WHERE category_id='movies' AND native_id='BV1xxx';

UPDATE category_members SET status='rejected', reviewer='me', reviewed_at=datetime('now')
WHERE category_id='movies' AND native_id='BV1yyy';

-- 置顶 + 人工排序(越小越前)
UPDATE category_members SET pinned=1, rank=0
WHERE category_id='movies' AND native_id='BV1zzz';
```

### 9.4 步骤三：物化成运行时快照

构建快照时,有 `approved` 行的分类**直接用库里的固定清单**(顺序:`pinned` > `rank` > `score`),
没有的分类回退实时聚合:

```bash
MEDIA_SERVER_CATALOG_DB=data/catalog.db go run . -build-snapshots
# movies 有 2 条 approved → 该分类秒回 2 条(不打上游);其余分类回退实时聚合
```

线上运行时,后台定期刷新(`runSnapshotRefresher`)同样走这条 catalog-first 路径:已审分类发固定
清单、未审分类继续实时聚合。**签名 URL 仍每请求生成**,库与落盘快照只存原始字段(无 sig/token)。

> 一句话:`-catalog-seed` 灌候选 → `-catalog-review`(或 SQL)审校 → `-build-snapshots`(或后台刷新)物化。
> 某分类一旦有 approved 行,它就变成“服务端只发你审过的固定清单”。
