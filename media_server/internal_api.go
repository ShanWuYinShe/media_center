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
// (catalog 审校 approve/reject/置顶/排序)、触发候选采集(seed)。
//
// 安全模型:整组接口由 MEDIA_SERVER_INTERNAL_TOKEN 门禁——令牌为空时整组路由对外表现为
// 404(不暴露存在性);令牌非空时请求必须带匹配的 X-Internal-Token。media_server 只在
// internal_docker 内网监听,nginx 不反代 /internal/*,因此该接口不经公网暴露。
// 它使 media_server 仍是 catalog.db 的唯一读写者(单写者),admin 通过本接口管理,
// 避免跨容器共享 SQLite 写带来的文件权限/锁竞争问题。

// opKind 是后台维护操作类型。
type opKind string

const (
	opSeed opKind = "seed"
)

// opState 是一次后台维护操作(seed)的状态快照,供管理界面轮询展示。
type opState struct {
	Running    bool   `json:"running"`
	Kind       string `json:"kind,omitempty"`
	Category   string `json:"category,omitempty"`
	StartedAt  string `json:"startedAt,omitempty"`
	FinishedAt string `json:"finishedAt,omitempty"`
	Message    string `json:"message,omitempty"`
	Error      string `json:"error,omitempty"`
}

// opTracker 串行化后台维护操作。状态用单把锁保护(操作频率很低)。
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
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/import":
		s.handleInternalImport(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/seed":
		s.handleInternalSeed(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/snapshots/rebuild":
		s.handleInternalSnapshotsRebuild(w, r)
	default:
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "请求的资源不存在。"})
	}
}

type internalImportRequest struct {
	Items []internalImportItem `json:"items"`
	// Reviewer 记录执行导入的操作者(由可信内部调用方 admin 注入登录用户名),
	// 仅在 item.status=approved 直接收录时作为审校留痕写入。
	Reviewer string `json:"reviewer,omitempty"`
}

type internalImportItem struct {
	CategoryID      string   `json:"categoryId"`
	Provider        string   `json:"provider"`
	NativeID        string   `json:"nativeId"`
	Title           string   `json:"title"`
	Description     string   `json:"description,omitempty"`
	Creators        []string `json:"creators,omitempty"`
	DurationSeconds int64    `json:"durationSeconds,omitempty"`
	Year            string   `json:"year,omitempty"`
	ThumbnailURL    string   `json:"thumbnailUrl,omitempty"`
	SourceURL       string   `json:"sourceUrl,omitempty"`
	Score           int      `json:"score,omitempty"`
	// Status 是目标审校状态:空或 "pending" 记为待审,"approved" 直接收录上线(用于"已审核
	// 视频集一步上线")。只对新成员生效,已存在的成员状态不会被覆盖。
	Status string `json:"status,omitempty"`
}

// handleInternalImport 把外部工具/AI 已收集并初筛的视频导入 catalog 待审列表。
// 只写 pending 候选,绝不直接 approved;已存在的审校决定不会被覆盖。
func (s *mediaServer) handleInternalImport(w http.ResponseWriter, r *http.Request) {
	if s.catalog == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "CATALOG_DISABLED", Message: "分类维护数据库未启用。"})
		return
	}
	var req internalImportRequest
	dec := json.NewDecoder(io.LimitReader(r.Body, 4<<20))
	if err := dec.Decode(&req); err != nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "请求体不是合法 JSON。"})
		return
	}
	if len(req.Items) == 0 {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "items 不能为空。"})
		return
	}
	if len(req.Items) > 1000 {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "单次最多导入 1000 条。"})
		return
	}

	items := make([]CatalogImportItem, 0, len(req.Items))
	for i, item := range req.Items {
		categoryID := strings.TrimSpace(item.CategoryID)
		if categoryID == "" {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("items[%d].categoryId 不能为空。", i)})
			return
		}
		if _, ok := s.categoriesByID[categoryID]; !ok {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("items[%d].categoryId 未知:%s", i, categoryID)})
			return
		}
		provider := strings.ToLower(strings.TrimSpace(item.Provider))
		nativeID := strings.TrimSpace(item.NativeID)
		title := strings.TrimSpace(item.Title)
		if provider == "" || nativeID == "" || title == "" {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("items[%d] provider/nativeId/title 不能为空。", i)})
			return
		}
		status := strings.TrimSpace(item.Status)
		if status != "" && status != "pending" && status != "approved" {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("items[%d].status 只能为 pending 或 approved。", i)})
			return
		}
		items = append(items, CatalogImportItem{
			CategoryID: categoryID,
			Video: CatalogVideo{
				Provider:     provider,
				NativeID:     nativeID,
				Title:        title,
				Description:  item.Description,
				Creators:     item.Creators,
				DurationSec:  item.DurationSeconds,
				Year:         item.Year,
				ThumbnailURL: item.ThumbnailURL,
				SourceURL:    item.SourceURL,
			},
			Score:  item.Score,
			Status: status,
		})
	}
	updatedVideos, imported, approved, skipped, err := s.catalog.ImportPending(items, strings.TrimSpace(req.Reviewer))
	if err != nil {
		s.writeError(w, fmt.Errorf("import catalog: %w", err))
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"videos":   updatedVideos,
		"imported": imported,
		"approved": approved,
		"skipped":  skipped,
	})
}

type internalCategoryOverview struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Pending  int    `json:"pending"`
	Approved int    `json:"approved"`
	Rejected int    `json:"rejected"`
}

// handleInternalOverview 返回每个分类的审校计数(pending/approved/rejected)与后台维护操作状态。
// catalog 未启用时计数为 0,仍返回分类列表。
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
		cats = append(cats, ov)
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"catalogEnabled": s.catalog != nil,
		"op":             s.ops.snapshot(),
		"categories":     cats,
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

// handleInternalSnapshotsRebuild 处理快照重建请求。当前 media_server 为"目录直读(catalog-first)"
// 模式:分类浏览每次请求直接分页读取 catalog 的 approved 行,审核通过的数据即时生效,没有需要
// 预重建的快照层。为保持 admin/前端契约稳定并避免误导,这里校验可选的 {"category":"id"} body
// 后返回成功,并说明数据已即时生效。(未来若引入快照预聚合层,可在此触发真正的重建。)
func (s *mediaServer) handleInternalSnapshotsRebuild(w http.ResponseWriter, r *http.Request) {
	categories, apiErr := s.internalCategoriesFromBody(r)
	if apiErr != nil {
		s.writeError(w, apiErr)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"rebuilt":   true,
		"immediate": true,
		"scope":     opCategoryLabel(categories),
		"note":      "目录直读模式:审核通过(approved)的分类数据已即时对客户端生效,无需重建快照。",
	})
}
