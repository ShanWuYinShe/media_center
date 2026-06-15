package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
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
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)", trimmed)
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

// ImportPending 把候选导入 catalog。每条按其 Status 落库:空或 "pending" 记为待审,"approved"
// 直接收录上线并记录 reviewer + reviewed_at(供"已审核视频集一步上线")。videos 元数据始终
// upsert 刷新;category_members 用 ON CONFLICT DO NOTHING,绝不覆盖已有审校决定——即只对新成员
// 生效,重复导入只刷新元数据并计入 skipped(要改既有成员状态请走 review)。
// 返回:videos(元数据写入数)、imported(新增成员数)、approved(其中直接收录数)、skipped(已存在跳过数)。
func (c *Catalog) ImportPending(items []CatalogImportItem, reviewer string) (videos int, imported int, approved int, skipped int, err error) {
	if len(items) == 0 {
		return 0, 0, 0, 0, nil
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
	for i, item := range items {
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
		res, execErr := tx.Exec(`
INSERT INTO category_members (category_id, provider, native_id, status, score, reviewer, reviewed_at)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(category_id, provider, native_id) DO NOTHING`,
			categoryID, provider, nativeID, status, item.Score, reviewerVal, reviewedAt,
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

func validMemberStatus(status string) bool {
	switch status {
	case "pending", "approved", "rejected":
		return true
	default:
		return false
	}
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
// 并记录审校人与时间。三者全为 nil 时报错(避免无意义的空更新)。返回受影响行数(0 表示
// 该 (分类,视频) 不存在)。admin 管理界面用它做 approve/reject/置顶/排序。
func (c *Catalog) ReviewMember(categoryID, provider, nativeID string, status *string, pinned *bool, rank *sql.NullInt64, reviewer string) (int64, error) {
	categoryID = strings.TrimSpace(categoryID)
	provider = strings.ToLower(strings.TrimSpace(provider))
	nativeID = strings.TrimSpace(nativeID)
	if categoryID == "" || provider == "" || nativeID == "" {
		return 0, fmt.Errorf("review member: category/provider/nativeID required")
	}
	var sets []string
	var args []any
	if status != nil {
		st := strings.TrimSpace(*status)
		if !validMemberStatus(st) {
			return 0, fmt.Errorf("review member: invalid status: %q", st)
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
		return 0, fmt.Errorf("review member: nothing to update")
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
	rows, err := c.db.Query(`SELECT category_id, status, COUNT(*) FROM category_members GROUP BY category_id, status`)
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
		`SELECT COUNT(*) FROM category_members WHERE category_id=? AND status=?`,
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

// seedCatalog 离线把各分类的实时聚合结果写入分类维护数据库作为 pending 候选,
// 供外部工具/人工审校。重复运行只新增候选,绝不覆盖已有的审校决定(approved/rejected)。
func (s *mediaServer) seedCatalog(ctx context.Context) error {
	return s.seedCatalogCategories(ctx, s.categories)
}

// seedCatalogCategories 对给定分类集合执行 seed(供 -catalog-seed 全量及内部接口按分类触发)。
func (s *mediaServer) seedCatalogCategories(ctx context.Context, categories []ContentCategory) error {
	if s.catalog == nil {
		return fmt.Errorf("seed catalog: catalog not opened")
	}
	// 审校池固定抓取一批较大的候选,给人工更多可选项;预算放宽,优先抓全抓准。
	const seedTarget = 120
	for _, category := range categories {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		buildCtx, cancel := context.WithTimeout(ctx, 3*time.Minute)
		items, _, err := s.collectCategoryItems(buildCtx, category, seedTarget, catalogSeedFetchBudget(seedTarget))
		cancel()
		if err != nil {
			log.Printf("catalog seed: collect failed category=%s: %v", category.ID, err)
			continue
		}

		added := 0
		for _, item := range items {
			provider, nativeID, ok := s.resolveVideoRef(item.ID)
			if !ok {
				continue
			}
			if err := s.catalog.UpsertVideo(CatalogVideo{
				Provider:     provider,
				NativeID:     nativeID,
				Title:        item.Title,
				Description:  item.Description,
				Creators:     item.Creators,
				DurationSec:  item.DurationSeconds,
				Year:         item.Year,
				ThumbnailURL: item.ThumbnailURL,
				SourceURL:    catalogSourceURL(provider, nativeID),
			}); err != nil {
				log.Printf("catalog seed: upsert failed id=%s: %v", item.ID, err)
				continue
			}
			isNew, err := s.catalog.AddPendingMember(category.ID, provider, nativeID, relevanceScore(item, category))
			if err != nil {
				log.Printf("catalog seed: add member failed id=%s: %v", item.ID, err)
				continue
			}
			if isNew {
				added++
			}
		}
		pending, _ := s.catalog.CountByStatus(category.ID, "pending")
		approved, _ := s.catalog.CountByStatus(category.ID, "approved")
		log.Printf("catalog seed: category=%s candidates=%d new=%d pending=%d approved=%d",
			category.ID, len(items), added, pending, approved)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return nil
}

func catalogSeedFetchBudget(targetSize int) int {
	budget := targetSize/4 + 8
	if budget < 12 {
		budget = 12
	}
	if budget > 60 {
		budget = 60
	}
	return budget
}

// catalogSourceURL 给人工审校生成一个可点击的原始观看地址(目前仅 bilibili)。
func catalogSourceURL(provider, nativeID string) string {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "bilibili":
		return "https://www.bilibili.com/video/" + strings.TrimSpace(nativeID)
	default:
		return ""
	}
}
