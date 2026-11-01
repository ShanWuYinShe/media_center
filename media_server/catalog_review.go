package main

import (
	"database/sql"
	"fmt"
	"strings"
	"time"
)

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
		// 孤儿视频连同合集关系一起清理,避免 collection_members 留下
		// 永远匹配不到 videos 的悬空行。
		if _, execErr = tx.Exec(`DELETE FROM collection_members WHERE provider=? AND native_id=?`, provider, nativeID); execErr != nil {
			err = fmt.Errorf("delete orphan collection member: %w", execErr)
			return 0, false, err
		}
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
// 这样审校界面看到的顺序即上线后排前的顺序。categoryID="" 表示所有分类,limit<=0 不限;
// offset>=0 时跳过前 offset 条(分页用)。
func (c *Catalog) ListMembers(categoryID, status string, limit, offset int) ([]PendingReviewItem, error) {
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
		if offset > 0 {
			query.WriteString(" OFFSET ?")
			args = append(args, offset)
		}
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

// CountMembers 统计与 ListMembers 相同过滤范围(category/status)的成员总数,
// 供管理界面分页展示"共 N 条"。ListMembers 只返回一页,不能拿页长度当 total。
func (c *Catalog) CountMembers(categoryID, status string) (int, error) {
	if c == nil {
		return 0, nil
	}
	var (
		query strings.Builder
		args  []any
	)
	query.WriteString(`SELECT COUNT(*) FROM category_members WHERE 1=1`)
	if categoryID = strings.TrimSpace(categoryID); categoryID != "" {
		query.WriteString(" AND category_id = ?")
		args = append(args, categoryID)
	}
	if status = strings.TrimSpace(status); status != "" {
		if !validMemberStatus(status) {
			return 0, fmt.Errorf("count members: invalid status: %q", status)
		}
		query.WriteString(" AND status = ?")
		args = append(args, status)
	}
	var total int
	if err := c.db.QueryRow(query.String(), args...).Scan(&total); err != nil {
		return 0, fmt.Errorf("count members: %w", err)
	}
	return total, nil
}

// PendingReviews 返回待审(status='pending')候选,排序同 ListMembers。
// -catalog-review 子命令复用它逐条展示与放行/拒绝。
func (c *Catalog) PendingReviews(opts PendingReviewsOptions) ([]PendingReviewItem, error) {
	return c.ListMembers(opts.CategoryID, "pending", opts.Limit, 0)
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
	rows, err := c.db.Query(`SELECT category_id, status, COUNT(*) FROM category_members WHERE ` + notPageGlobCond("provider", "native_id") + ` GROUP BY category_id, status`)
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
		`SELECT COUNT(*) FROM category_members WHERE category_id=? AND status=? AND `+notPageGlobCond("provider", "native_id")+``,
		strings.TrimSpace(categoryID), strings.TrimSpace(status),
	).Scan(&n)
	if err != nil {
		return 0, err
	}
	return n, nil
}
