package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

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
	rows, err := c.db.Query(`SELECT DISTINCT provider FROM category_members WHERE status = 'approved' AND ` + notPageGlobCond("provider", "native_id") + ` ORDER BY provider ASC`)
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
	rows, err := c.db.Query(`SELECT DISTINCT category_id FROM category_members WHERE provider = ? AND status = 'approved' AND `+notPageGlobCond("provider", "native_id")+` ORDER BY category_id ASC`, provider)
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

// DurationBounds 是分类列表的可选时长过滤(单位秒,含边界)。零值字段表示
// 该侧不过滤;两端都为零等价于不启用过滤。时长缺失(NULL)的视频在启用过滤
// 时被排除(无法判定所属档位)。
type DurationBounds struct {
	MinSec int64
	MaxSec int64
}

func (b DurationBounds) enabled() bool { return b.MinSec > 0 || b.MaxSec > 0 }

// conditions 返回时长过滤的 SQL 片段与参数(videos 表别名必须是 v)。
func (b DurationBounds) conditions() (string, []any) {
	var (
		parts []string
		args  []any
	)
	if b.MinSec > 0 {
		parts = append(parts, "v.duration_sec >= ?")
		args = append(args, b.MinSec)
	}
	if b.MaxSec > 0 {
		parts = append(parts, "v.duration_sec <= ?")
		args = append(args, b.MaxSec)
	}
	if len(parts) == 0 {
		return "", nil
	}
	return " AND " + strings.Join(parts, " AND "), args
}

// ApprovedPageForProvider 返回某 provider 某分类 approved 列表的一页与总条数。
func (c *Catalog) ApprovedPageForProvider(provider, categoryID string, page, pageSize int) ([]CatalogVideo, int, error) {
	return c.ApprovedPageForProviderFiltered(provider, categoryID, page, pageSize, DurationBounds{})
}

// ApprovedPageForProviderFiltered 在 ApprovedPageForProvider 基础上叠加时长
// 过滤。计数与列表共用同一 WHERE,保证 total 与分页内容一致;翻页时过滤条件
// 由调用方原样透传(同一档位内 total 不变)。
func (c *Catalog) ApprovedPageForProviderFiltered(provider, categoryID string, page, pageSize int, bounds DurationBounds) ([]CatalogVideo, int, error) {
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
	durationCond, durationArgs := bounds.conditions()
	countArgs := append([]any{provider, categoryID}, durationArgs...)
	var total int
	if err := c.db.QueryRow(
		`SELECT COUNT(*) FROM category_members m JOIN videos v ON v.provider = m.provider AND v.native_id = m.native_id
WHERE m.provider = ? AND m.category_id = ? AND m.status = 'approved' AND `+notPageGlobCond("m.provider", "v.native_id")+durationCond,
		countArgs...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count approved for provider: %w", err)
	}
	if total == 0 {
		return []CatalogVideo{}, 0, nil
	}
	offset := (page - 1) * pageSize
	listArgs := append([]any{provider, categoryID}, durationArgs...)
	listArgs = append(listArgs, pageSize, offset)
	rows, err := c.db.Query(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM category_members m
JOIN videos v ON v.provider = m.provider AND v.native_id = m.native_id
WHERE m.provider = ? AND m.category_id = ? AND m.status = 'approved' AND `+notPageGlobCond("m.provider", "v.native_id")+durationCond+`
ORDER BY m.pinned DESC, (m.rank IS NULL), m.rank ASC, m.score DESC, v.native_id ASC
LIMIT ? OFFSET ?`, listArgs...)
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
WHERE m.category_id = ? AND m.status = 'approved' AND ` + notPageGlobCond("m.provider", "v.native_id") + `
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
		`SELECT COUNT(*) FROM category_members WHERE category_id = ? AND status = 'approved' AND `+notPageGlobCond("provider", "native_id")+``,
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
WHERE m.category_id = ? AND m.status = 'approved' AND `+notPageGlobCond("m.provider", "v.native_id")+`
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
	where := `m.status = 'approved' AND ` + notPageGlobCond("m.provider", "v.native_id") + ` AND (
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
