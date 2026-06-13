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
// 每条候选视频与它在各分类下的审校决定,服务端构建快照时只读取 approved 行物化成
// 运行时快照(详见 CATEGORY_CURATION_GUIDE.md)。
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

// ApprovedForCategory 返回某分类已通过的成员,按 置顶 > 人工序 > 分数 排序,
// 用于物化成运行时快照。limit<=0 表示不限。
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
	if s.catalog == nil {
		return fmt.Errorf("seed catalog: catalog not opened")
	}
	// 审校池要比运行时目标更大,给人工更多可选项;预算放宽,优先抓全抓准。
	seedTarget := s.config.SnapshotTargetSize * 2
	if seedTarget < 120 {
		seedTarget = 120
	}
	for _, category := range s.categories {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		buildCtx, cancel := context.WithTimeout(ctx, 3*time.Minute)
		items, _, err := s.collectCategoryItems(buildCtx, category, seedTarget, snapshotFetchBudget(seedTarget))
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

// catalogSourceURL 给人工审校生成一个可点击的原始观看地址(目前仅 bilibili)。
func catalogSourceURL(provider, nativeID string) string {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "bilibili":
		return "https://www.bilibili.com/video/" + strings.TrimSpace(nativeID)
	default:
		return ""
	}
}
