package main

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

// 内部管理接口(/internal/*):仅供内网可信调用方(workwork 的 admin)管理分类维护数据库
// (catalog 审校 approve/reject/置顶/排序)、触发候选采集(seed)和快照重建。
//
// 安全模型:整组接口由 MEDIA_SERVER_INTERNAL_TOKEN 门禁——令牌为空时整组路由对外表现为
// 404(不暴露存在性);令牌非空时请求必须带匹配的 X-Internal-Token。media_server 只在
// internal_docker 内网监听,nginx 不反代 /internal/*,因此该接口不经公网暴露。
// 它使 media_server 仍是 catalog.db 的唯一读写者(单写者),admin 通过本接口管理,
// 避免跨容器共享 SQLite 写带来的文件权限/锁竞争问题。

// opKind 是后台维护操作类型。
type opKind string

const (
	opSeed    opKind = "seed"
	opRebuild opKind = "rebuild"
)

// opState 是一次后台维护操作(seed / 快照重建)的状态快照,供管理界面轮询展示。
type opState struct {
	Running    bool   `json:"running"`
	Kind       string `json:"kind,omitempty"`
	Category   string `json:"category,omitempty"`
	StartedAt  string `json:"startedAt,omitempty"`
	FinishedAt string `json:"finishedAt,omitempty"`
	Message    string `json:"message,omitempty"`
	Error      string `json:"error,omitempty"`
}

// opTracker 串行化后台维护操作:同一时间只允许一个 seed/rebuild 在跑,避免对上游突发打流
// 或并发写快照。状态用单把锁保护(操作频率很低)。
type opTracker struct {
	mu    sync.Mutex
	state opState
}

// tryStart 原子地把状态置为“运行中”;若已有操作在跑返回 false。
func (t *opTracker) tryStart(kind opKind, category string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.state.Running {
		return false
	}
	t.state = opState{
		Running:   true,
		Kind:      string(kind),
		Category:  category,
		StartedAt: time.Now().UTC().Format(time.RFC3339),
	}
	return true
}

func (t *opTracker) finish(message string, err error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.state.Running = false
	t.state.FinishedAt = time.Now().UTC().Format(time.RFC3339)
	t.state.Message = message
	if err != nil {
		t.state.Error = err.Error()
	} else {
		t.state.Error = ""
	}
}

func (t *opTracker) snapshot() opState {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.state
}

// internalAuthorized 用常量时间比较校验 X-Internal-Token。令牌未配置时一律拒绝。
func (s *mediaServer) internalAuthorized(r *http.Request) bool {
	want := s.config.InternalToken
	if want == "" {
		return false
	}
	got := strings.TrimSpace(r.Header.Get("X-Internal-Token"))
	if got == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

// handleInternal 是 /internal/* 的统一入口:先门禁,再按路径分发。
func (s *mediaServer) handleInternal(w http.ResponseWriter, r *http.Request) {
	if s.config.InternalToken == "" {
		// 未配置令牌:对外表现为路由不存在,避免暴露内部接口的存在性。
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "请求的资源不存在。"})
		return
	}
	if !s.internalAuthorized(r) {
		s.writeError(w, &apiError{Status: http.StatusUnauthorized, Code: "UNAUTHORIZED", Message: "内部接口令牌无效。"})
		return
	}
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/internal/catalog/overview":
		s.handleInternalOverview(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/internal/catalog/items":
		s.handleInternalItems(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/review":
		s.handleInternalReview(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/seed":
		s.handleInternalSeed(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/snapshots/rebuild":
		s.handleInternalRebuild(w, r)
	default:
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "请求的资源不存在。"})
	}
}

type internalSnapshotInfo struct {
	Items     int    `json:"items"`
	UpdatedAt string `json:"updatedAt"`
}

type internalCategoryOverview struct {
	ID       string                `json:"id"`
	Title    string                `json:"title"`
	Pending  int                   `json:"pending"`
	Approved int                   `json:"approved"`
	Rejected int                   `json:"rejected"`
	Snapshot *internalSnapshotInfo `json:"snapshot,omitempty"`
}

// handleInternalOverview 返回每个分类的审校计数(pending/approved/rejected)与当前快照概况,
// 以及后台维护操作状态。catalog 未启用时计数为 0,仍返回分类列表与快照信息。
func (s *mediaServer) handleInternalOverview(w http.ResponseWriter, _ *http.Request) {
	var counts map[string]CategoryStatusCounts
	if s.catalog != nil {
		c, err := s.catalog.OverviewCounts()
		if err != nil {
			s.writeError(w, fmt.Errorf("overview counts: %w", err))
			return
		}
		counts = c
	}
	cats := make([]internalCategoryOverview, 0, len(s.categories))
	for _, category := range s.categories {
		ov := internalCategoryOverview{ID: category.ID, Title: category.Title}
		if c, ok := counts[category.ID]; ok {
			ov.Pending, ov.Approved, ov.Rejected = c.Pending, c.Approved, c.Rejected
		}
		if s.snapshots != nil {
			if snap, ok := s.snapshots.Get(category.ID); ok {
				ov.Snapshot = &internalSnapshotInfo{
					Items:     len(snap.Items),
					UpdatedAt: snap.UpdatedAt.UTC().Format(time.RFC3339),
				}
			}
		}
		cats = append(cats, ov)
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"catalogEnabled":     s.catalog != nil,
		"snapshotEnabled":    s.config.SnapshotEnabled,
		"snapshotTargetSize": s.config.SnapshotTargetSize,
		"op":                 s.ops.snapshot(),
		"categories":         cats,
	})
}

// internalReviewItem 是审校界面用的成员视图:稳定原生标识 + 原始元数据 + 审校字段。
// 缩略图为原始地址(仅供审校预览),签名 /asset 代理 URL 不在此生成。
type internalReviewItem struct {
	Provider        string   `json:"provider"`
	NativeID        string   `json:"nativeId"`
	CategoryID      string   `json:"categoryId"`
	Status          string   `json:"status"`
	Title           string   `json:"title"`
	Description     string   `json:"description,omitempty"`
	Creators        []string `json:"creators,omitempty"`
	DurationSeconds int64    `json:"durationSeconds"`
	Year            string   `json:"year,omitempty"`
	ThumbnailURL    string   `json:"thumbnailUrl,omitempty"`
	SourceURL       string   `json:"sourceUrl,omitempty"`
	Score           int      `json:"score"`
	Pinned          bool     `json:"pinned"`
	Rank            *int64   `json:"rank,omitempty"`
	Reviewer        string   `json:"reviewer,omitempty"`
	ReviewedAt      string   `json:"reviewedAt,omitempty"`
}

func toInternalReviewItem(it PendingReviewItem) internalReviewItem {
	out := internalReviewItem{
		Provider: it.Provider, NativeID: it.NativeID, CategoryID: it.CategoryID, Status: it.Status,
		Title: it.Title, Description: it.Description, Creators: it.Creators,
		DurationSeconds: it.DurationSec, Year: it.Year, ThumbnailURL: it.ThumbnailURL, SourceURL: it.SourceURL,
		Score: it.Score, Pinned: it.Pinned, Reviewer: it.Reviewer, ReviewedAt: it.ReviewedAt,
	}
	if it.Rank.Valid {
		r := it.Rank.Int64
		out.Rank = &r
	}
	return out
}

// handleInternalItems 列出某分类(可选某状态)的成员,供审校界面分页/筛选展示。
func (s *mediaServer) handleInternalItems(w http.ResponseWriter, r *http.Request) {
	if s.catalog == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "CATALOG_DISABLED", Message: "分类维护数据库未启用。"})
		return
	}
	q := r.URL.Query()
	category := strings.TrimSpace(q.Get("category"))
	status := strings.TrimSpace(q.Get("status"))
	if status != "" && !validMemberStatus(status) {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "status 取值不合法。"})
		return
	}
	limit := positiveInt(q.Get("limit"), 200)
	if limit > 1000 {
		limit = 1000
	}
	items, err := s.catalog.ListMembers(category, status, limit)
	if err != nil {
		s.writeError(w, fmt.Errorf("list members: %w", err))
		return
	}
	out := make([]internalReviewItem, 0, len(items))
	for _, it := range items {
		out = append(out, toInternalReviewItem(it))
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"items": out, "total": len(out)})
}

// internalReviewRequest 是一次审校变更:status/pinned/rank 任一非空即更新该字段。
// clearRank=true 表示把 rank 清空(NULL);否则用 rank 字段(若提供)。
type internalReviewRequest struct {
	CategoryID string  `json:"categoryId"`
	Provider   string  `json:"provider"`
	NativeID   string  `json:"nativeId"`
	Status     *string `json:"status,omitempty"`
	Pinned     *bool   `json:"pinned,omitempty"`
	Rank       *int64  `json:"rank,omitempty"`
	ClearRank  bool    `json:"clearRank,omitempty"`
	// Reviewer 记录执行审校的操作者(由可信内部调用方 admin 注入登录用户名)。留空时回退为 "admin"。
	Reviewer string `json:"reviewer,omitempty"`
}

// handleInternalReview 对单个 (分类,视频) 成员应用审校变更(approve/reject/置顶/排序)。
func (s *mediaServer) handleInternalReview(w http.ResponseWriter, r *http.Request) {
	if s.catalog == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "CATALOG_DISABLED", Message: "分类维护数据库未启用。"})
		return
	}
	var req internalReviewRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "请求体不是合法 JSON。"})
		return
	}
	var rankArg *sql.NullInt64
	if req.ClearRank {
		rankArg = &sql.NullInt64{Valid: false}
	} else if req.Rank != nil {
		rankArg = &sql.NullInt64{Int64: *req.Rank, Valid: true}
	}
	reviewer := strings.TrimSpace(req.Reviewer)
	if reviewer == "" {
		reviewer = "admin"
	}
	affected, err := s.catalog.ReviewMember(req.CategoryID, req.Provider, req.NativeID, req.Status, req.Pinned, rankArg, reviewer)
	if err != nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: err.Error()})
		return
	}
	if affected == 0 {
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到该分类下的视频成员。"})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"updated": affected})
}

// internalCategoriesFromBody 解析可选的 {"category":"id"} body:空 body / 空 category 表示
// 全部分类;未知分类返回 400。
func (s *mediaServer) internalCategoriesFromBody(r *http.Request) ([]ContentCategory, *apiError) {
	var req struct {
		Category string `json:"category"`
	}
	if r.Body != nil {
		dec := json.NewDecoder(io.LimitReader(r.Body, 1<<16))
		if err := dec.Decode(&req); err != nil && err != io.EOF {
			return nil, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "请求体不是合法 JSON。"}
		}
	}
	id := strings.TrimSpace(req.Category)
	if id == "" {
		return s.categories, nil
	}
	cat, ok := s.categoriesByID[id]
	if !ok {
		return nil, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "未知分类:" + id}
	}
	return []ContentCategory{cat}, nil
}

func opCategoryLabel(categories []ContentCategory) string {
	if len(categories) == 1 {
		return categories[0].ID
	}
	return "all"
}

// handleInternalSeed 异步把(指定/全部)分类的实时聚合结果灌成 pending 候选。返回 202,
// 进度通过 overview 的 op 字段轮询。
func (s *mediaServer) handleInternalSeed(w http.ResponseWriter, r *http.Request) {
	if s.catalog == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "CATALOG_DISABLED", Message: "分类维护数据库未启用。"})
		return
	}
	categories, apiErr := s.internalCategoriesFromBody(r)
	if apiErr != nil {
		s.writeError(w, apiErr)
		return
	}
	if !s.ops.tryStart(opSeed, opCategoryLabel(categories)) {
		s.writeError(w, &apiError{Status: http.StatusConflict, Code: "OP_RUNNING", Message: "已有维护操作在进行中,请稍后再试。"})
		return
	}
	go func(cats []ContentCategory) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		err := s.seedCatalogCategories(ctx, cats)
		s.ops.finish(fmt.Sprintf("seeded %d categories", len(cats)), err)
		if err != nil {
			log.Printf("internal seed: %v", err)
		}
	}(categories)
	s.writeJSON(w, http.StatusAccepted, map[string]any{"started": true, "op": s.ops.snapshot()})
}

// handleInternalRebuild 异步重建(指定/全部)分类的运行时快照(catalog-first 物化)。
func (s *mediaServer) handleInternalRebuild(w http.ResponseWriter, r *http.Request) {
	categories, apiErr := s.internalCategoriesFromBody(r)
	if apiErr != nil {
		s.writeError(w, apiErr)
		return
	}
	if !s.ops.tryStart(opRebuild, opCategoryLabel(categories)) {
		s.writeError(w, &apiError{Status: http.StatusConflict, Code: "OP_RUNNING", Message: "已有维护操作在进行中,请稍后再试。"})
		return
	}
	go func(cats []ContentCategory) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		var firstErr error
		built := 0
		for _, category := range cats {
			if ctx.Err() != nil {
				firstErr = ctx.Err()
				break
			}
			if _, err := s.refreshSnapshotForCategory(ctx, category); err != nil {
				log.Printf("internal rebuild: category=%s: %v", category.ID, err)
				if firstErr == nil {
					firstErr = err
				}
				continue
			}
			built++
		}
		s.ops.finish(fmt.Sprintf("rebuilt %d/%d categories", built, len(cats)), firstErr)
	}(categories)
	s.writeJSON(w, http.StatusAccepted, map[string]any{"started": true, "op": s.ops.snapshot()})
}
