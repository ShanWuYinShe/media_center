package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// Catalog 是“分类维护数据库”(system of record):外部工具/人工在这里维护
// 每条候选视频与它在各分类下的审校决定,分类浏览时直接分页读取 approved 行
// (详见 CATEGORY_CURATION_GUIDE.md)。
//
// 该层用纯 Go 的 modernc.org/sqlite(无需 cgo),开 WAL 支持并发读 + 单写。
// schema 用 CREATE TABLE IF NOT EXISTS 兜底,外部工具也可直接按文档结构写库。
type Catalog struct {
	db *sql.DB
}

// CatalogVideo 是 videos 表的一行:一条视频的稳定原生标识 + 抓取到的元数据。
type CatalogVideo struct {
	Provider     string
	NativeID     string
	Title        string
	Description  string
	Creators     []string
	DurationSec  int64
	Year         string
	ThumbnailURL string
	SourceURL    string
}

type CatalogImportItem struct {
	CategoryID string
	Video      CatalogVideo
	Score      int
	// Status 是导入时的目标审校状态:空或 "pending" 记为待审,"approved" 直接收录上线。
	Status string
	// Reason 是该候选的审校备注/AI 分析理由(如 "AI: 标题与内容不符")。
	// 写入 category_members.reason 列，供人工审核时参考；不参与任何自动筛选。
	Reason string
	// Pinned/Rank 为可选的展示次序控制,主要用于 export→import 的忠实回放(把另一处导出的
	// 置顶/人工序原样带过来)。nil 表示不设置(置顶=false,序=NULL)。只对新成员生效。
	Pinned *bool
	Rank   *int64
}

// OpenCatalog 打开(必要时创建)分类维护数据库,确保 schema 存在。
func OpenCatalog(path string) (*Catalog, error) {
	trimmed := strings.TrimSpace(path)
	if trimmed == "" {
		return nil, fmt.Errorf("catalog path is empty")
	}
	if dir := filepath.Dir(trimmed); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create catalog dir: %w", err)
		}
	}
	q := url.Values{
		"_pragma": []string{
			"busy_timeout(5000)",
			"journal_mode(WAL)",
			"foreign_keys(ON)",
		},
	}
	dsn := fmt.Sprintf("file:%s?%s", trimmed, q.Encode())
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open catalog: %w", err)
	}
	// modernc 的连接是串行的;限制为单连接避免 WAL 写锁竞争带来的 SQLITE_BUSY。
	db.SetMaxOpenConns(1)
	c := &Catalog{db: db}
	if err := c.ensureSchema(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return c, nil
}

func (c *Catalog) Close() error {
	if c == nil || c.db == nil {
		return nil
	}
	return c.db.Close()
}

func (c *Catalog) ensureSchema() error {
	const schema = `
CREATE TABLE IF NOT EXISTS videos (
  provider      TEXT NOT NULL,
  native_id     TEXT NOT NULL,
  title         TEXT NOT NULL,
  description   TEXT,
  creators      TEXT,
  duration_sec  INTEGER,
  year          TEXT,
  thumbnail_url TEXT,
  source_url    TEXT,
  fetched_at    TEXT,
  PRIMARY KEY (provider, native_id)
);
CREATE TABLE IF NOT EXISTS category_members (
  category_id  TEXT NOT NULL,
  provider     TEXT NOT NULL,
  native_id    TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'pending',
  pinned       INTEGER NOT NULL DEFAULT 0,
  rank         INTEGER,
  score        INTEGER,
  reason       TEXT,
  reviewer     TEXT,
  reviewed_at  TEXT,
  PRIMARY KEY (category_id, provider, native_id),
  FOREIGN KEY (provider, native_id) REFERENCES videos(provider, native_id)
);
CREATE INDEX IF NOT EXISTS idx_members_cat_status ON category_members(category_id, status);
-- 公开读路径都带 provider 过滤:分类列表(provider+category+status)、
-- provider 过滤搜索与分类归属查询(provider+status)。
CREATE INDEX IF NOT EXISTS idx_members_provider_cat_status ON category_members(provider, category_id, status);
CREATE INDEX IF NOT EXISTS idx_members_provider_status ON category_members(provider, status);
-- 合集(如 B 站 ugc_season):由外部采集工具经 /internal/catalog/collections 写入,
-- 只存分组关系,不引入新内容;公开队列只返回其中已审核(approved)的视频。
CREATE TABLE IF NOT EXISTS collections (
  provider      TEXT NOT NULL,
  collection_id TEXT NOT NULL,
  title         TEXT NOT NULL DEFAULT '',
  updated_at    TEXT,
  PRIMARY KEY (provider, collection_id)
);
CREATE TABLE IF NOT EXISTS collection_members (
  provider      TEXT NOT NULL,
  collection_id TEXT NOT NULL,
  native_id     TEXT NOT NULL,
  position      INTEGER NOT NULL,
  PRIMARY KEY (provider, collection_id, native_id),
  FOREIGN KEY (provider, collection_id) REFERENCES collections(provider, collection_id)
);
CREATE INDEX IF NOT EXISTS idx_collection_members_video ON collection_members(provider, native_id);
CREATE INDEX IF NOT EXISTS idx_collection_members_order ON collection_members(provider, collection_id, position);`
	if _, err := c.db.Exec(schema); err != nil {
		return fmt.Errorf("ensure catalog schema: %w", err)
	}
	return nil
}

// UpsertVideo 写入/刷新一条视频的元数据(按 provider+native_id 去重)。
func (c *Catalog) UpsertVideo(v CatalogVideo) error {
	provider := strings.ToLower(strings.TrimSpace(v.Provider))
	nativeID := strings.TrimSpace(v.NativeID)
	if provider == "" || nativeID == "" {
		return fmt.Errorf("upsert video: provider/nativeID required")
	}
	creators, err := json.Marshal(v.Creators)
	if err != nil {
		return err
	}
	_, err = c.db.Exec(`
INSERT INTO videos (provider, native_id, title, description, creators, duration_sec, year, thumbnail_url, source_url, fetched_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(provider, native_id) DO UPDATE SET
  title=excluded.title,
  description=excluded.description,
  creators=excluded.creators,
  duration_sec=excluded.duration_sec,
  year=excluded.year,
  thumbnail_url=excluded.thumbnail_url,
  source_url=excluded.source_url,
  fetched_at=excluded.fetched_at`,
		provider, nativeID, strings.TrimSpace(v.Title), v.Description, string(creators),
		v.DurationSec, strings.TrimSpace(v.Year), strings.TrimSpace(v.ThumbnailURL),
		strings.TrimSpace(v.SourceURL), time.Now().UTC().Format(time.RFC3339),
	)
	if err != nil {
		return fmt.Errorf("upsert video: %w", err)
	}
	return nil
}

// AddPendingMember 把一条视频登记为某分类的待审候选;已存在(含已审)的不覆盖,
// 这样重复 seed 只新增候选,绝不抹掉已有的审校决定。返回是否新增。
func (c *Catalog) AddPendingMember(categoryID, provider, nativeID string, score int) (bool, error) {
	categoryID = strings.TrimSpace(categoryID)
	provider = strings.ToLower(strings.TrimSpace(provider))
	nativeID = strings.TrimSpace(nativeID)
	if categoryID == "" || provider == "" || nativeID == "" {
		return false, fmt.Errorf("add member: category/provider/nativeID required")
	}

	res, err := c.db.Exec(`
INSERT INTO category_members (category_id, provider, native_id, status, score)
VALUES (?, ?, ?, 'pending', ?)
ON CONFLICT(category_id, provider, native_id) DO NOTHING`,
		categoryID, provider, nativeID, score,
	)
	if err != nil {
		return false, fmt.Errorf("add member: %w", err)
	}
	affected, _ := res.RowsAffected()
	return affected > 0, nil
}

// stripPageSuffix 仅对 bilibili 移除分 P 后缀 _pN,返回基础 native_id。
// 例如 "BV1xx_p3" → "BV1xx", "BV1xx" → "BV1xx"。
// 其他 provider 的 native_id 以 "_p<数字>" 结尾时不做截断(如未来 youtube 视频
// ID 形如 "xxx_p2026"),避免聚合逻辑误伤。
func stripPageSuffix(provider, nativeID string) string {
	if provider != "bilibili" {
		return nativeID
	}
	idx := strings.LastIndex(nativeID, "_p")
	if idx < 0 {
		return nativeID
	}
	suffix := nativeID[idx+2:]
	if _, err := strconv.Atoi(suffix); err != nil {
		return nativeID
	}
	return nativeID[:idx]
}

// notPageGlobCond 返回「排除分P页记录」的 SQL 条件:页记录指 bilibili 分P
// 导入产生的 native_id 以 _p<纯数字> 结尾的条目(与 stripPageSuffix 的定义
// 一致,三段模式覆盖到 _p999 页)。历史实现用 LIKE '%\_p%' 子串匹配,会把
// **任何位置**含 "_p" 的合法 ID(如 YouTube 的 ab_pdefX9Zk)从公开列表静默
// 隐藏——能入库、能 resolve 播放,却永远不出现在 providers/categories/videos/
// search。SQLite 的 GLOB 全串匹配天然锚定结尾,误伤面收窄到「恰以 _p<数字>
// 结尾」的极端形态(导入路径已聚合分P,该形态实际不再产生)。
// col 需带表前缀时传 "v.native_id" 这类限定名。
func notPageGlobCond(providerCol, nativeCol string) string {
	// 只有 bilibili 的 native_id 会带分P后缀(stripPageSuffix 同样只对
	// bilibili 生效)。不能对所有 provider 一刀切:合法的 YouTube 11 字符
	// ID 完全可能以 "_p1" 结尾(如 abcdefgh_p1),那样会被公开列表静默隐藏。
	return fmt.Sprintf(
		"(%[1]s <> 'bilibili' OR (%[2]s NOT GLOB '*_p[0-9]' AND %[2]s NOT GLOB '*_p[0-9][0-9]' AND %[2]s NOT GLOB '*_p[0-9][0-9][0-9]'))",
		providerCol, nativeCol)
}

// pageGlobCond 是 notPageGlobCond 的反相(选中页记录),供 ConsolidatePages
// 清理工具扫描存量脏数据。
func pageGlobCond(providerCol, nativeCol string) string {
	return fmt.Sprintf(
		"(%[1]s = 'bilibili' AND (%[2]s GLOB '*_p[0-9]' OR %[2]s GLOB '*_p[0-9][0-9]' OR %[2]s GLOB '*_p[0-9][0-9][0-9]'))",
		providerCol, nativeCol)
}

// ImportPending 把候选导入 catalog。每条按其 Status 落库:空或 "pending" 记为待审,"approved"
// 直接收录上线并记录 reviewer + reviewed_at(供"已审核视频集一步上线")。videos 元数据始终
// upsert 刷新;category_members 用 ON CONFLICT DO NOTHING,绝不覆盖已有审校决定——即只对新成员
// 生效,重复导入只刷新元数据并计入 skipped(要改既有成员状态请走 review)。
//
// 同一视频的分 P 条目(含 _pN 后缀,如 BV1xx_p1 / BV1xx_p2)会在导入时自动聚合成一条:
// 以首次出现的标题/描述等元数据为准,时长累加,只创建一条 category_member。这避免了
// 分 P 被拆成多条独立候选的脏数据,也确保公共 API 的 _pN 过滤层不会意外吞掉整部视频。
//
// 写入按 importBatchSize 条分批提交:catalog 用单 SQLite 连接(SetMaxOpenConns(1)),
// 大事务持有唯一连接期间全部公开读(目录浏览/搜索)都要排队,导入千条会卡住全站
// 数秒到数十秒。校验全部前置——任何一条非法直接报错、一条不写;批间 DB 失败时已
// 提交批次保留,写路径全幂等(upsert + ON CONFLICT DO NOTHING),重导即可补齐。
// 返回:videos(元数据写入数)、imported(新增成员数)、approved(其中直接收录数)、skipped(已存在跳过数);
// 出错时计数为出错前已提交批次的实际值。
func (c *Catalog) ImportPending(items []CatalogImportItem, reviewer string) (videos int, imported int, approved int, skipped int, err error) {
	if len(items) == 0 {
		return 0, 0, 0, 0, nil
	}

	// 聚合分 P 条目:按 (category, provider, base_id) 分组,合并元数据。
	type importGroup struct {
		categoryID string
		provider   string
		nativeID   string
		first      CatalogImportItem
		totalSec   int64
		count      int
	}
	groups := make(map[string]*importGroup)
	for _, item := range items {
		cat := strings.TrimSpace(item.CategoryID)
		prov := strings.ToLower(strings.TrimSpace(item.Video.Provider))
		nid := strings.TrimSpace(item.Video.NativeID)
		if cat == "" || prov == "" || nid == "" {
			continue
		}
		baseID := stripPageSuffix(prov, nid)
		key := cat + "|" + prov + "|" + baseID
		g, ok := groups[key]
		if !ok {
			groups[key] = &importGroup{
				categoryID: cat,
				provider:   prov,
				nativeID:   baseID,
				first:      item,
				totalSec:   item.Video.DurationSec,
				count:      1,
			}
		} else {
			g.totalSec += item.Video.DurationSec
			g.count++
		}
	}

	merged := make([]CatalogImportItem, 0, len(groups))
	for _, g := range groups {
		item := g.first
		item.Video.NativeID = g.nativeID
		if g.totalSec > 0 {
			item.Video.DurationSec = g.totalSec
		}
		if g.count > 1 {
			log.Printf("catalog: aggregated %d page entries into %s/%s/%s",
				g.count, g.provider, g.nativeID, g.categoryID)
		}
		merged = append(merged, item)
	}
	// 预处理与校验全部前置(开事务之前):任何一条非法直接报错、一条不写,
	// 保持"坏输入零写入"的旧语义;归一化结果直接落回 merged。
	for i := range merged {
		item := &merged[i]
		item.CategoryID = strings.TrimSpace(item.CategoryID)
		item.Video.Provider = strings.ToLower(strings.TrimSpace(item.Video.Provider))
		item.Video.NativeID = strings.TrimSpace(item.Video.NativeID)
		item.Video.Title = strings.TrimSpace(item.Video.Title)
		if item.CategoryID == "" || item.Video.Provider == "" || item.Video.NativeID == "" || item.Video.Title == "" {
			return 0, 0, 0, 0, fmt.Errorf("import item %d: category/provider/nativeID/title required", i)
		}
		if item.Status = strings.TrimSpace(item.Status); item.Status == "" {
			item.Status = "pending"
		}
		if item.Status != "pending" && item.Status != "approved" {
			return 0, 0, 0, 0, fmt.Errorf("import item %d: invalid status %q (want pending/approved)", i, item.Status)
		}
	}

	reviewer = strings.TrimSpace(reviewer)
	for start := 0; start < len(merged); start += importBatchSize {
		end := start + importBatchSize
		if end > len(merged) {
			end = len(merged)
		}
		batchVideos, batchImported, batchApproved, batchSkipped, batchErr := c.importBatch(merged[start:end], reviewer, start)
		videos += batchVideos
		imported += batchImported
		approved += batchApproved
		skipped += batchSkipped
		if batchErr != nil {
			// 已提交批次保留(写路径幂等,重导可补齐);返回已提交部分的计数便于排障。
			return videos, imported, approved, skipped, batchErr
		}
	}
	return videos, imported, approved, skipped, nil
}

// importBatchSize 是 ImportPending 单事务的条数上限。
const importBatchSize = 200

// importBatch 在单个事务里写入一批已校验条目;失败回滚本批(之前的批次已提交,
// 保留)。offset 仅用于错误信息定位全局条目序号。
func (c *Catalog) importBatch(batch []CatalogImportItem, reviewer string, offset int) (videos int, imported int, approved int, skipped int, err error) {
	tx, err := c.db.Begin()
	if err != nil {
		return 0, 0, 0, 0, fmt.Errorf("begin import: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback()
		}
	}()
	for i, item := range batch {
		creators, marshalErr := json.Marshal(item.Video.Creators)
		if marshalErr != nil {
			return 0, 0, 0, 0, marshalErr
		}
		if _, err = tx.Exec(`
INSERT INTO videos (provider, native_id, title, description, creators, duration_sec, year, thumbnail_url, source_url, fetched_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(provider, native_id) DO UPDATE SET
  title=excluded.title,
  description=excluded.description,
  creators=excluded.creators,
  duration_sec=excluded.duration_sec,
  year=excluded.year,
  thumbnail_url=excluded.thumbnail_url,
  source_url=excluded.source_url,
  fetched_at=excluded.fetched_at`,
			item.Video.Provider, item.Video.NativeID, item.Video.Title, item.Video.Description, string(creators),
			item.Video.DurationSec, strings.TrimSpace(item.Video.Year), strings.TrimSpace(item.Video.ThumbnailURL),
			strings.TrimSpace(item.Video.SourceURL), time.Now().UTC().Format(time.RFC3339),
		); err != nil {
			return 0, 0, 0, 0, fmt.Errorf("import video %d: %w", offset+i, err)
		}
		videos++
		// pending 成员 reviewer/reviewed_at 留 NULL;approved 直接收录时记录审校留痕。
		var reviewerVal, reviewedAt any
		if item.Status == "approved" {
			reviewerVal = reviewer
			reviewedAt = time.Now().UTC().Format(time.RFC3339)
		}
		// pinned 默认 false;rank 默认 NULL。export→import 回放时按导出值还原(只对新成员生效)。
		pinnedVal := 0
		if item.Pinned != nil && *item.Pinned {
			pinnedVal = 1
		}
		var rankVal any
		if item.Rank != nil {
			rankVal = *item.Rank
		}
		// reason 透传候选备注/AI 分析理由(可空),写入 category_members.reason 供审校参考。
		reasonVal := strings.TrimSpace(item.Reason)
		res, execErr := tx.Exec(`
INSERT INTO category_members (category_id, provider, native_id, status, score, pinned, rank, reviewer, reviewed_at, reason)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(category_id, provider, native_id) DO NOTHING`,
			item.CategoryID, item.Video.Provider, item.Video.NativeID, item.Status, item.Score, pinnedVal, rankVal, reviewerVal, reviewedAt, reasonVal,
		)
		if execErr != nil {
			err = fmt.Errorf("import member %d: %w", offset+i, execErr)
			return 0, 0, 0, 0, err
		}
		affected, _ := res.RowsAffected()
		if affected > 0 {
			imported++
			if item.Status == "approved" {
				approved++
			}
		} else {
			skipped++
		}
	}
	if err = tx.Commit(); err != nil {
		return 0, 0, 0, 0, fmt.Errorf("commit import: %w", err)
	}
	return videos, imported, approved, skipped, nil
}
