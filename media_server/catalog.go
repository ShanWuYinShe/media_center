package main

import (
	"database/sql"
	"encoding/json"
	"errors"
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
CREATE INDEX IF NOT EXISTS idx_members_cat_status ON category_members(category_id, status);`
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

// ImportPending 把候选导入 catalog。每条按其 Status 落库:空或 "pending" 记为待审,"approved"
// 直接收录上线并记录 reviewer + reviewed_at(供"已审核视频集一步上线")。videos 元数据始终
// upsert 刷新;category_members 用 ON CONFLICT DO NOTHING,绝不覆盖已有审校决定——即只对新成员
// 生效,重复导入只刷新元数据并计入 skipped(要改既有成员状态请走 review)。
//
// 同一视频的分 P 条目(含 _pN 后缀,如 BV1xx_p1 / BV1xx_p2)会在导入时自动聚合成一条:
// 以首次出现的标题/描述等元数据为准,时长累加,只创建一条 category_member。这避免了
// 分 P 被拆成多条独立候选的脏数据,也确保公共 API 的 _pN 过滤层不会意外吞掉整部视频。
// 返回:videos(元数据写入数)、imported(新增成员数)、approved(其中直接收录数)、skipped(已存在跳过数)。
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
	reviewer = strings.TrimSpace(reviewer)
	tx, err := c.db.Begin()
	if err != nil {
		return 0, 0, 0, 0, fmt.Errorf("begin import: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback()
		}
	}()
	for i, item := range merged {
		categoryID := strings.TrimSpace(item.CategoryID)
		provider := strings.ToLower(strings.TrimSpace(item.Video.Provider))
		nativeID := strings.TrimSpace(item.Video.NativeID)
		title := strings.TrimSpace(item.Video.Title)
		if categoryID == "" || provider == "" || nativeID == "" || title == "" {
			return 0, 0, 0, 0, fmt.Errorf("import item %d: category/provider/nativeID/title required", i)
		}
		status := strings.TrimSpace(item.Status)
		if status == "" {
			status = "pending"
		}
		if status != "pending" && status != "approved" {
			return 0, 0, 0, 0, fmt.Errorf("import item %d: invalid status %q (want pending/approved)", i, status)
		}
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
			provider, nativeID, title, item.Video.Description, string(creators),
			item.Video.DurationSec, strings.TrimSpace(item.Video.Year), strings.TrimSpace(item.Video.ThumbnailURL),
			strings.TrimSpace(item.Video.SourceURL), time.Now().UTC().Format(time.RFC3339),
		); err != nil {
			return 0, 0, 0, 0, fmt.Errorf("import video %d: %w", i, err)
		}
		videos++
		// pending 成员 reviewer/reviewed_at 留 NULL;approved 直接收录时记录审校留痕。
		var reviewerVal, reviewedAt any
		if status == "approved" {
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
			categoryID, provider, nativeID, status, item.Score, pinnedVal, rankVal, reviewerVal, reviewedAt, reasonVal,
		)
		if execErr != nil {
			err = fmt.Errorf("import member %d: %w", i, execErr)
			return 0, 0, 0, 0, err
		}
		affected, _ := res.RowsAffected()
		if affected > 0 {
			imported++
			if status == "approved" {
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

// SetMemberStatus 更新某分类成员的审校状态(approved/rejected/pending),记录审校人。
func (c *Catalog) SetMemberStatus(categoryID, provider, nativeID, status, reviewer string) (int64, error) {
	categoryID = strings.TrimSpace(categoryID)
	provider = strings.ToLower(strings.TrimSpace(provider))
	nativeID = strings.TrimSpace(nativeID)
	status = strings.TrimSpace(status)
	if !validMemberStatus(status) {
		return 0, fmt.Errorf("invalid status: %q", status)
	}
	res, err := c.db.Exec(`
UPDATE category_members SET status=?, reviewer=?, reviewed_at=?
WHERE category_id=? AND provider=? AND native_id=?`,
		status, strings.TrimSpace(reviewer), time.Now().UTC().Format(time.RFC3339),
		categoryID, provider, nativeID,
	)
	if err != nil {
		return 0, fmt.Errorf("set member status: %w", err)
	}
	affected, _ := res.RowsAffected()
	return affected, nil
}

// DeleteMember 硬删除某分类下的一个视频成员(用于"删"——彻底移除,区别于 reject 的软标记)。
// 在事务内:先删 category_members 行;若该视频已不被任何分类引用,连带删除 videos 元数据(避免
// 孤儿堆积)。返回删除的成员行数(0 表示不存在)与是否连带删除了 video。
func (c *Catalog) DeleteMember(categoryID, provider, nativeID string) (deleted int64, videoDeleted bool, err error) {
	categoryID = strings.TrimSpace(categoryID)
	provider = strings.ToLower(strings.TrimSpace(provider))
	nativeID = strings.TrimSpace(nativeID)
	if categoryID == "" || provider == "" || nativeID == "" {
		return 0, false, fmt.Errorf("delete member: category/provider/nativeID required")
	}
	tx, err := c.db.Begin()
	if err != nil {
		return 0, false, fmt.Errorf("begin delete: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback()
		}
	}()
	res, execErr := tx.Exec(
		`DELETE FROM category_members WHERE category_id=? AND provider=? AND native_id=?`,
		categoryID, provider, nativeID,
	)
	if execErr != nil {
		err = fmt.Errorf("delete member: %w", execErr)
		return 0, false, err
	}
	deleted, _ = res.RowsAffected()
	if deleted == 0 {
		err = tx.Commit()
		return 0, false, err
	}
	var remaining int
	if err = tx.QueryRow(
		`SELECT COUNT(*) FROM category_members WHERE provider=? AND native_id=?`,
		provider, nativeID,
	).Scan(&remaining); err != nil {
		err = fmt.Errorf("count remaining members: %w", err)
		return 0, false, err
	}
	if remaining == 0 {
		if _, execErr = tx.Exec(`DELETE FROM videos WHERE provider=? AND native_id=?`, provider, nativeID); execErr != nil {
			err = fmt.Errorf("delete orphan video: %w", execErr)
			return 0, false, err
		}
		videoDeleted = true
	}
	if err = tx.Commit(); err != nil {
		return 0, false, fmt.Errorf("commit delete: %w", err)
	}
	return deleted, videoDeleted, nil
}

func validMemberStatus(status string) bool {
	switch status {
	case "pending", "approved", "rejected":
		return true
	default:
		return false
	}
}

// FindVideo 按 provider + native_id 查询单条视频元数据(无视审校状态)。
// 找不到时返回 (zero, false, nil);只有 SQL 错误才返回非 nil err。
// 供 client-resolved provider(如 bilibili)的详情/播放接口直接读 catalog,
// 不再调上游 API。
func (c *Catalog) FindVideo(provider, nativeID string) (CatalogVideo, bool, error) {
	provider = strings.TrimSpace(provider)
	nativeID = strings.TrimSpace(nativeID)
	if provider == "" || nativeID == "" || c == nil {
		return CatalogVideo{}, false, nil
	}
	row := c.db.QueryRow(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM videos v
WHERE v.provider = ? AND v.native_id = ?`, provider, nativeID)
	var v CatalogVideo
	var creators sql.NullString
	var description, year, thumb, source sql.NullString
	var duration sql.NullInt64
	if err := row.Scan(&v.Provider, &v.NativeID, &v.Title, &description, &creators, &duration, &year, &thumb, &source); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return CatalogVideo{}, false, nil
		}
		return CatalogVideo{}, false, fmt.Errorf("find video: %w", err)
	}
	v.Description = description.String
	v.Year = year.String
	v.ThumbnailURL = thumb.String
	v.SourceURL = source.String
	v.DurationSec = duration.Int64
	v.Creators = decodeCreators(creators.String)
	return v, true, nil
}

// DistinctProviders 返回 catalog 中有 approved 视频的所有 provider(升序)。
func (c *Catalog) DistinctProviders() ([]string, error) {
	if c == nil {
		return nil, nil
	}
	rows, err := c.db.Query(`SELECT DISTINCT provider FROM category_members WHERE status = 'approved' AND native_id NOT LIKE '%\_p%' ESCAPE '\' ORDER BY provider ASC`)
	if err != nil {
		return nil, fmt.Errorf("query distinct providers: %w", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var provider string
		if err := rows.Scan(&provider); err != nil {
			return nil, fmt.Errorf("scan provider: %w", err)
		}
		out = append(out, provider)
	}
	return out, rows.Err()
}

// CategoriesForProvider 返回某 provider 有 approved 视频的所有 category_id(升序)。
func (c *Catalog) CategoriesForProvider(provider string) ([]string, error) {
	provider = strings.ToLower(strings.TrimSpace(provider))
	if provider == "" || c == nil {
		return nil, nil
	}
	rows, err := c.db.Query(`SELECT DISTINCT category_id FROM category_members WHERE provider = ? AND status = 'approved' AND native_id NOT LIKE '%\_p%' ESCAPE '\' ORDER BY category_id ASC`, provider)
	if err != nil {
		return nil, fmt.Errorf("query categories for provider: %w", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var categoryID string
		if err := rows.Scan(&categoryID); err != nil {
			return nil, fmt.Errorf("scan category_id: %w", err)
		}
		out = append(out, categoryID)
	}
	return out, rows.Err()
}

// ApprovedPageForProvider 返回某 provider 某分类 approved 列表的一页与总条数。
func (c *Catalog) ApprovedPageForProvider(provider, categoryID string, page, pageSize int) ([]CatalogVideo, int, error) {
	provider = strings.ToLower(strings.TrimSpace(provider))
	categoryID = strings.TrimSpace(categoryID)
	if provider == "" || categoryID == "" || c == nil {
		return []CatalogVideo{}, 0, nil
	}
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 1
	}
	var total int
	if err := c.db.QueryRow(
		`SELECT COUNT(*) FROM category_members WHERE provider = ? AND category_id = ? AND status = 'approved' AND native_id NOT LIKE '%\_p%' ESCAPE '\'`,
		provider, categoryID,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count approved for provider: %w", err)
	}
	if total == 0 {
		return []CatalogVideo{}, 0, nil
	}
	offset := (page - 1) * pageSize
	rows, err := c.db.Query(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM category_members m
JOIN videos v ON v.provider = m.provider AND v.native_id = m.native_id
WHERE m.provider = ? AND m.category_id = ? AND m.status = 'approved' AND v.native_id NOT LIKE '%\_p%' ESCAPE '\'
ORDER BY m.pinned DESC, (m.rank IS NULL), m.rank ASC, m.score DESC, v.native_id ASC
LIMIT ? OFFSET ?`, provider, categoryID, pageSize, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("query approved page for provider: %w", err)
	}
	defer rows.Close()
	out := make([]CatalogVideo, 0, pageSize)
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var description, year, thumb, source sql.NullString
		var duration sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &description, &creators, &duration, &year, &thumb, &source); err != nil {
			return nil, 0, fmt.Errorf("scan approved page for provider: %w", err)
		}
		v.Description = description.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = source.String
		v.DurationSec = duration.Int64
		v.Creators = decodeCreators(creators.String)
		out = append(out, v)
	}
	return out, total, rows.Err()
}

// ApprovedForCategory 返回某分类已通过的成员,按 置顶 > 人工序 > 分数 排序。limit<=0 表示不限。
func (c *Catalog) ApprovedForCategory(categoryID string, limit int) ([]CatalogVideo, error) {
	categoryID = strings.TrimSpace(categoryID)
	if categoryID == "" {
		return nil, nil
	}
	query := `
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM category_members m
JOIN videos v ON v.provider = m.provider AND v.native_id = m.native_id
WHERE m.category_id = ? AND m.status = 'approved'
ORDER BY m.pinned DESC, (m.rank IS NULL), m.rank ASC, m.score DESC, v.native_id ASC`
	args := []any{categoryID}
	if limit > 0 {
		query += "\nLIMIT ?"
		args = append(args, limit)
	}
	rows, err := c.db.Query(query, args...)
	if err != nil {
		return nil, fmt.Errorf("query approved: %w", err)
	}
	defer rows.Close()
	var out []CatalogVideo
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var description, year, thumb, source sql.NullString
		var duration sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &description, &creators, &duration, &year, &thumb, &source); err != nil {
			return nil, fmt.Errorf("scan approved: %w", err)
		}
		v.Description = description.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = source.String
		v.DurationSec = duration.Int64
		v.Creators = decodeCreators(creators.String)
		out = append(out, v)
	}
	return out, rows.Err()
}

// ApprovedPage 返回某分类 approved 列表的一页与总条数。page/pageSize 非法时自动规整到最小值 1。
func (c *Catalog) ApprovedPage(categoryID string, page, pageSize int) ([]CatalogVideo, int, error) {
	categoryID = strings.TrimSpace(categoryID)
	if categoryID == "" {
		return nil, 0, nil
	}
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 1
	}

	var total int
	if err := c.db.QueryRow(
		`SELECT COUNT(*) FROM category_members WHERE category_id = ? AND status = 'approved'`,
		categoryID,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count approved: %w", err)
	}
	if total == 0 {
		return []CatalogVideo{}, 0, nil
	}

	offset := (page - 1) * pageSize
	rows, err := c.db.Query(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM category_members m
JOIN videos v ON v.provider = m.provider AND v.native_id = m.native_id
WHERE m.category_id = ? AND m.status = 'approved'
ORDER BY m.pinned DESC, (m.rank IS NULL), m.rank ASC, m.score DESC, v.native_id ASC
LIMIT ? OFFSET ?`, categoryID, pageSize, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("query approved page: %w", err)
	}
	defer rows.Close()

	out := make([]CatalogVideo, 0, pageSize)
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var description, year, thumb, source sql.NullString
		var duration sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &description, &creators, &duration, &year, &thumb, &source); err != nil {
			return nil, 0, fmt.Errorf("scan approved page: %w", err)
		}
		v.Description = description.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = source.String
		v.DurationSec = duration.Int64
		v.Creators = decodeCreators(creators.String)
		out = append(out, v)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

// PendingReviewItem 是一条待审候选:CatalogVideo 的元数据 + 该视频在某分类下的审校元信息。
// 供 -catalog-review 子命令逐条展示与放行/拒绝。
type PendingReviewItem struct {
	CatalogVideo
	CategoryID string
	Status     string
	Score      int
	Pinned     bool
	Rank       sql.NullInt64
	Reason     string
	Reviewer   string
	ReviewedAt string
}

// PendingReviewsOptions 控制 PendingReviews 的查询范围。
type PendingReviewsOptions struct {
	CategoryID string // 空=所有分类
	Limit      int    // <=0 不限
}

// ListMembers 返回某分类下指定状态的成员(status="" 表示所有状态),附带视频元数据与
// 审校字段。排序与 ApprovedForCategory 一致(置顶 > 人工序 > 分数 > native_id),
// 这样审校界面看到的顺序即上线后排前的顺序。categoryID="" 表示所有分类,limit<=0 不限。
func (c *Catalog) ListMembers(categoryID, status string, limit int) ([]PendingReviewItem, error) {
	var (
		query strings.Builder
		args  []any
	)
	query.WriteString(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url,
       m.category_id, m.status, m.score, m.pinned, m.rank, m.reason, m.reviewer, m.reviewed_at
FROM category_members m
JOIN videos v ON v.provider = m.provider AND v.native_id = m.native_id
WHERE 1=1`)
	if categoryID = strings.TrimSpace(categoryID); categoryID != "" {
		query.WriteString(" AND m.category_id = ?")
		args = append(args, categoryID)
	}
	if status = strings.TrimSpace(status); status != "" {
		if !validMemberStatus(status) {
			return nil, fmt.Errorf("list members: invalid status: %q", status)
		}
		query.WriteString(" AND m.status = ?")
		args = append(args, status)
	}
	query.WriteString("\nORDER BY m.pinned DESC, (m.rank IS NULL), m.rank ASC, m.score DESC, v.native_id ASC")
	if limit > 0 {
		query.WriteString("\nLIMIT ?")
		args = append(args, limit)
	}
	rows, err := c.db.Query(query.String(), args...)
	if err != nil {
		return nil, fmt.Errorf("query members: %w", err)
	}
	defer rows.Close()
	var out []PendingReviewItem
	for rows.Next() {
		var it PendingReviewItem
		var creators sql.NullString
		var description, year, thumb, source, statusVal, reason, reviewer, reviewedAt sql.NullString
		var duration, score sql.NullInt64
		var pinned int64
		if err := rows.Scan(
			&it.Provider, &it.NativeID, &it.Title, &description, &creators, &duration, &year, &thumb, &source,
			&it.CategoryID, &statusVal, &score, &pinned, &it.Rank, &reason, &reviewer, &reviewedAt,
		); err != nil {
			return nil, fmt.Errorf("scan member: %w", err)
		}
		it.Description = description.String
		it.Year = year.String
		it.ThumbnailURL = thumb.String
		it.SourceURL = source.String
		it.DurationSec = duration.Int64
		it.Creators = decodeCreators(creators.String)
		it.Status = statusVal.String
		it.Score = int(score.Int64)
		it.Pinned = pinned != 0
		it.Reason = reason.String
		it.Reviewer = reviewer.String
		it.ReviewedAt = reviewedAt.String
		out = append(out, it)
	}
	return out, rows.Err()
}

// PendingReviews 返回待审(status='pending')候选,排序同 ListMembers。
// -catalog-review 子命令复用它逐条展示与放行/拒绝。
func (c *Catalog) PendingReviews(opts PendingReviewsOptions) ([]PendingReviewItem, error) {
	return c.ListMembers(opts.CategoryID, "pending", opts.Limit)
}

// ReviewMember 更新某分类成员的审校字段:status / pinned / rank 任一非 nil 即更新该列,
// validationError 是业务校验错误:消息可安全返回客户端,与底层 DB/SQL 错误
// (可能泄露实现细节)区分。handler 层用 errors.As 识别,而不是依赖"是否被
// %w wrap"这类隐式约定(任何 wrap 链改动都会改变对外行为)。
type validationError struct{ msg string }

func (e *validationError) Error() string { return e.msg }

// 并记录审校人与时间。三者全为 nil 时报错(避免无意义的空更新)。返回受影响行数(0 表示
// 该 (分类,视频) 不存在)。admin 管理界面用它做 approve/reject/置顶/排序。
func (c *Catalog) ReviewMember(categoryID, provider, nativeID string, status *string, pinned *bool, rank *sql.NullInt64, reviewer string) (int64, error) {
	categoryID = strings.TrimSpace(categoryID)
	provider = strings.ToLower(strings.TrimSpace(provider))
	nativeID = strings.TrimSpace(nativeID)
	if categoryID == "" || provider == "" || nativeID == "" {
		return 0, &validationError{"review member: category/provider/nativeID required"}
	}
	var sets []string
	var args []any
	if status != nil {
		st := strings.TrimSpace(*status)
		if !validMemberStatus(st) {
			return 0, &validationError{fmt.Sprintf("review member: invalid status: %q", st)}
		}
		sets = append(sets, "status=?")
		args = append(args, st)
	}
	if pinned != nil {
		pv := 0
		if *pinned {
			pv = 1
		}
		sets = append(sets, "pinned=?")
		args = append(args, pv)
	}
	if rank != nil {
		if rank.Valid {
			sets = append(sets, "rank=?")
			args = append(args, rank.Int64)
		} else {
			sets = append(sets, "rank=NULL")
		}
	}
	if len(sets) == 0 {
		return 0, &validationError{"review member: nothing to update"}
	}
	sets = append(sets, "reviewer=?", "reviewed_at=?")
	args = append(args, strings.TrimSpace(reviewer), time.Now().UTC().Format(time.RFC3339), categoryID, provider, nativeID)
	query := "UPDATE category_members SET " + strings.Join(sets, ", ") +
		" WHERE category_id=? AND provider=? AND native_id=?"
	res, err := c.db.Exec(query, args...)
	if err != nil {
		return 0, fmt.Errorf("review member: %w", err)
	}
	affected, _ := res.RowsAffected()
	return affected, nil
}

// CategoryStatusCounts 是某分类各审校状态的成员计数,供管理概览展示。
type CategoryStatusCounts struct {
	Pending  int `json:"pending"`
	Approved int `json:"approved"`
	Rejected int `json:"rejected"`
}

// OverviewCounts 一次性统计每个分类各状态的成员数。空库返回空 map(非 nil 错误)。
func (c *Catalog) OverviewCounts() (map[string]CategoryStatusCounts, error) {
	rows, err := c.db.Query(`SELECT category_id, status, COUNT(*) FROM category_members WHERE native_id NOT LIKE '%\_p%' ESCAPE '\' GROUP BY category_id, status`)
	if err != nil {
		return nil, fmt.Errorf("overview counts: %w", err)
	}
	defer rows.Close()
	out := make(map[string]CategoryStatusCounts)
	for rows.Next() {
		var cat, status string
		var n int
		if err := rows.Scan(&cat, &status, &n); err != nil {
			return nil, fmt.Errorf("scan overview: %w", err)
		}
		counts := out[cat]
		switch status {
		case "pending":
			counts.Pending = n
		case "approved":
			counts.Approved = n
		case "rejected":
			counts.Rejected = n
		}
		out[cat] = counts
	}
	return out, rows.Err()
}

// CountByStatus 统计某分类下某状态的成员数(用于命令行概览)。
func (c *Catalog) CountByStatus(categoryID, status string) (int, error) {
	var n int
	err := c.db.QueryRow(
		`SELECT COUNT(*) FROM category_members WHERE category_id=? AND status=? AND native_id NOT LIKE '%\_p%' ESCAPE '\'`,
		strings.TrimSpace(categoryID), strings.TrimSpace(status),
	).Scan(&n)
	if err != nil {
		return 0, err
	}
	return n, nil
}

func decodeCreators(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "null" {
		return nil
	}
	var out []string
	if err := json.Unmarshal([]byte(raw), &out); err != nil {
		return nil
	}
	return out
}

// escapeLike 转义 LIKE 模式中的通配符,使关键词按字面量子串匹配(配合 ESCAPE '\')。
func escapeLike(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// SearchApproved 只在已审核(approved)的分类数据里按关键词搜索,绝不查询上游。
// 匹配标题/简介/作者的子串(大小写不敏感),跨分类去重后按 置顶 > 分数 > native_id 排序,
// 返回一页结果与去重后的总命中数。query 为空或没有命中的 approved 数据时返回空列表。
// provider 非空时只搜索该 provider(如 App 端只支持 bilibili 播放,避免返回
// 其他 provider 无法播放的结果)。
func (c *Catalog) SearchApproved(query, provider string, page, pageSize int) ([]CatalogVideo, int, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return []CatalogVideo{}, 0, nil
	}
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 1
	}
	like := "%" + escapeLike(strings.ToLower(query)) + "%"
	const where = `m.status = 'approved' AND v.native_id NOT LIKE '%\_p%' ESCAPE '\' AND (
  lower(v.title) LIKE ? ESCAPE '\' OR
  lower(COALESCE(v.description, '')) LIKE ? ESCAPE '\' OR
  lower(COALESCE(v.creators, '')) LIKE ? ESCAPE '\')`
	args := []any{like, like, like}
	whereSQL := where
	if provider = strings.TrimSpace(strings.ToLower(provider)); provider != "" {
		whereSQL += ` AND v.provider = ?`
		args = append(args, provider)
	}

	var total int
	countSQL := `SELECT COUNT(*) FROM (
SELECT 1
FROM videos v
JOIN category_members m ON m.provider = v.provider AND m.native_id = v.native_id
WHERE ` + whereSQL + `
GROUP BY v.provider, v.native_id)`
	if err := c.db.QueryRow(countSQL, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count search: %w", err)
	}
	if total == 0 {
		return []CatalogVideo{}, 0, nil
	}

	offset := (page - 1) * pageSize
	listSQL := `
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url,
       MAX(m.pinned) AS top, MAX(COALESCE(m.score, 0)) AS sc
FROM videos v
JOIN category_members m ON m.provider = v.provider AND m.native_id = v.native_id
WHERE ` + whereSQL + `
GROUP BY v.provider, v.native_id
ORDER BY top DESC, sc DESC, v.native_id ASC
LIMIT ? OFFSET ?`
	queryArgs := append(append([]any{}, args...), pageSize, offset)
	rows, err := c.db.Query(listSQL, queryArgs...)
	if err != nil {
		return nil, 0, fmt.Errorf("query search: %w", err)
	}
	defer rows.Close()

	out := make([]CatalogVideo, 0, pageSize)
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var description, year, thumb, source sql.NullString
		var duration sql.NullInt64
		var top, score sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &description, &creators, &duration, &year, &thumb, &source, &top, &score); err != nil {
			return nil, 0, fmt.Errorf("scan search: %w", err)
		}
		v.Description = description.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = source.String
		v.DurationSec = duration.Int64
		v.Creators = decodeCreators(creators.String)
		out = append(out, v)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

// ConsolidatePages 清理 catalog 中已有的分 P 脏数据:合并同 base native_id 的 _pN 条目。
func (c *Catalog) ConsolidatePages() (groups int, deleted int, created int, err error) {
	const pagePattern = `%\_p%`

	// Step 1: 把所有 _pN 条目读入内存(提前释放连接,避免随后的事务死锁)
	rows, err := c.db.Query(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM videos v
WHERE v.native_id LIKE ? ESCAPE '\'
ORDER BY v.provider, v.native_id`, pagePattern)
	if err != nil {
		return 0, 0, 0, fmt.Errorf("query page videos: %w", err)
	}

	type pageEntry struct{ CatalogVideo }
	pageMap := make(map[string][]pageEntry)
	var allKeys []string
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var desc, year, thumb, src sql.NullString
		var dur sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &desc, &creators, &dur, &year, &thumb, &src); err != nil {
			rows.Close()
			return 0, 0, 0, fmt.Errorf("scan page video: %w", err)
		}
		v.Description = desc.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = src.String
		v.DurationSec = dur.Int64
		v.Creators = decodeCreators(creators.String)

		baseID := stripPageSuffix(v.Provider, v.NativeID)
		key := v.Provider + "|" + baseID
		if _, ok := pageMap[key]; !ok {
			allKeys = append(allKeys, key)
		}
		pageMap[key] = append(pageMap[key], pageEntry{v})
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, 0, 0, err
	}

	// Step 2: 读取所有 _pN 的分类成员(一次查出,避免逐条 Query)
	memberSQL := `SELECT provider, native_id, category_id, status, score, pinned, rank, COALESCE(reviewer,''), COALESCE(reviewed_at,'') FROM category_members WHERE provider=? AND native_id=?`
	type memberRow struct {
		provider, nativeID, catID, status, reviewer, reviewedAt string
		score, pinned                                           int64
		rank                                                    sql.NullInt64
	}
	pageMembers := make(map[string][]memberRow) // key: provider|native_id
	for _, key := range allKeys {
		entries := pageMap[key]
		for _, entry := range entries {
			mrows, qerr := c.db.Query(memberSQL, entry.Provider, entry.NativeID)
			if qerr != nil {
				return 0, 0, 0, fmt.Errorf("query members for %s/%s: %w", entry.Provider, entry.NativeID, qerr)
			}
			for mrows.Next() {
				var mr memberRow
				if err := mrows.Scan(&mr.provider, &mr.nativeID, &mr.catID, &mr.status, &mr.score, &mr.pinned, &mr.rank, &mr.reviewer, &mr.reviewedAt); err != nil {
					mrows.Close()
					return 0, 0, 0, fmt.Errorf("scan member: %w", err)
				}
				mkey := entry.Provider + "|" + entry.NativeID
				pageMembers[mkey] = append(pageMembers[mkey], mr)
			}
			mrows.Close()
		}
	}

	// Step 3: 检查哪些 base 视频已存在
	baseExists := make(map[string]bool)
	for _, key := range allKeys {
		parts := strings.SplitN(key, "|", 2)
		var exists bool
		_ = c.db.QueryRow(`SELECT 1 FROM videos WHERE provider=? AND native_id=?`, parts[0], parts[1]).Scan(&exists)
		baseExists[key] = exists
	}

	// Step 4: 在一个事务内完成所有写入
	tx, err := c.db.Begin()
	if err != nil {
		return 0, 0, 0, fmt.Errorf("begin tx: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback()
		}
	}()

	for _, key := range allKeys {
		entries := pageMap[key]
		parts := strings.SplitN(key, "|", 2)
		provider := parts[0]
		baseID := parts[1]

		if !baseExists[key] {
			first := entries[0]
			var totalSec int64
			for _, e := range entries {
				totalSec += e.DurationSec
			}
			creatorsJSON, _ := json.Marshal(first.Creators)
			if _, err = tx.Exec(`
INSERT INTO videos (provider, native_id, title, description, creators, duration_sec, year, thumbnail_url, source_url, fetched_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				provider, baseID, first.Title, first.Description, string(creatorsJSON),
				totalSec, first.Year, first.ThumbnailURL, first.SourceURL, time.Now().UTC().Format(time.RFC3339)); err != nil {
				return 0, 0, 0, fmt.Errorf("create base video %s/%s: %w", provider, baseID, err)
			}
			created++
		}

		for _, entry := range entries {
			mkey := entry.Provider + "|" + entry.NativeID
			for _, mr := range pageMembers[mkey] {
				if _, err = tx.Exec(`
INSERT INTO category_members (category_id, provider, native_id, status, score, pinned, rank, reviewer, reviewed_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(category_id, provider, native_id) DO NOTHING`,
					mr.catID, provider, baseID, mr.status, mr.score, mr.pinned, mr.rank, mr.reviewer, mr.reviewedAt); err != nil {
					return 0, 0, 0, fmt.Errorf("insert base member %s/%s/%s: %w", mr.catID, provider, baseID, err)
				}
			}
		}

		for _, entry := range entries {
			if _, err = tx.Exec(`DELETE FROM category_members WHERE provider=? AND native_id=?`, provider, entry.NativeID); err != nil {
				return 0, 0, 0, fmt.Errorf("delete member %s/%s: %w", provider, entry.NativeID, err)
			}
			res, delErr := tx.Exec(`DELETE FROM videos WHERE provider=? AND native_id=?`, provider, entry.NativeID)
			if delErr != nil {
				return 0, 0, 0, fmt.Errorf("delete video %s/%s: %w", provider, entry.NativeID, delErr)
			}
			if n, _ := res.RowsAffected(); n > 0 {
				deleted++
			}
		}
		groups++
	}

	if err = tx.Commit(); err != nil {
		return 0, 0, 0, fmt.Errorf("commit: %w", err)
	}
	return groups, deleted, created, nil
}
