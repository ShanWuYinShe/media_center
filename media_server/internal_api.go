package main

import (
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
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
	case r.Method == http.MethodGet && r.URL.Path == "/internal/catalog/export":
		s.handleInternalExport(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/review":
		s.handleInternalReview(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/delete":
		s.handleInternalDelete(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/internal/catalog/import":
		s.handleInternalImport(w, r)
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
	// Reason 是候选备注/AI 分析理由(可空),写入 category_members.reason 供人工审校参考。
	Reason string `json:"reason,omitempty"`
	// Status 是目标审校状态:空或 "pending" 记为待审,"approved" 直接收录上线(用于"已审核
	// 视频集一步上线")。只对新成员生效,已存在的成员状态不会被覆盖。
	Status string `json:"status,omitempty"`
	// Pinned/Rank 为可选展示次序控制,主要用于另一处 export 出来的数据原样回放(忠实迁移
	// 置顶/人工序)。省略即不设置。只对新成员生效。
	Pinned *bool  `json:"pinned,omitempty"`
	Rank   *int64 `json:"rank,omitempty"`
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
			Reason: item.Reason,
			Status: status,
			Pinned: item.Pinned,
			Rank:   item.Rank,
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
	// Reason 是候选备注/AI 分析理由，供人工审校参考。
	Reason     string `json:"reason,omitempty"`
	Pinned     bool   `json:"pinned"`
	Rank       *int64 `json:"rank,omitempty"`
	Reviewer   string `json:"reviewer,omitempty"`
	ReviewedAt string `json:"reviewedAt,omitempty"`
}

func toInternalReviewItem(it PendingReviewItem) internalReviewItem {
	out := internalReviewItem{
		Provider: it.Provider, NativeID: it.NativeID, CategoryID: it.CategoryID, Status: it.Status,
		Title: it.Title, Description: it.Description, Creators: it.Creators,
		DurationSeconds: it.DurationSec, Year: it.Year, ThumbnailURL: it.ThumbnailURL, SourceURL: it.SourceURL,
		Score: it.Score, Reason: it.Reason, Pinned: it.Pinned, Reviewer: it.Reviewer, ReviewedAt: it.ReviewedAt,
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
	offset := positiveInt(q.Get("offset"), 0)
	items, err := s.catalog.ListMembers(category, status, limit, offset)
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
		// 校验类错误(validationError)是可安全返回的业务消息;
		// 底层 SQL/DB 错误不泄露细节。
		var valErr *validationError
		if errors.As(err, &valErr) {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: valErr.Error()})
			return
		}
		log.Printf("review member: %v", err)
		s.writeError(w, fmt.Errorf("review member"))
		return
	}
	if affected == 0 {
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到该分类下的视频成员。"})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"updated": affected})
}

// internalDeleteRequest 是一次硬删除请求:按 (分类,视频) 定位要彻底移除的成员。
type internalDeleteRequest struct {
	CategoryID string `json:"categoryId"`
	Provider   string `json:"provider"`
	NativeID   string `json:"nativeId"`
}

// handleInternalDelete 硬删除某分类下的一个视频成员(增删改查的"删")。区别于 review 的软
// reject:这里把成员行真正从库中移除;若该视频不再被任何分类引用,连带清理 videos 元数据。
func (s *mediaServer) handleInternalDelete(w http.ResponseWriter, r *http.Request) {
	if s.catalog == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "CATALOG_DISABLED", Message: "分类维护数据库未启用。"})
		return
	}
	var req internalDeleteRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "请求体不是合法 JSON。"})
		return
	}
	if strings.TrimSpace(req.CategoryID) == "" || strings.TrimSpace(req.Provider) == "" || strings.TrimSpace(req.NativeID) == "" {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "categoryId/provider/nativeId 不能为空。"})
		return
	}
	deleted, videoDeleted, err := s.catalog.DeleteMember(req.CategoryID, req.Provider, req.NativeID)
	if err != nil {
		s.writeError(w, fmt.Errorf("delete member: %w", err))
		return
	}
	if deleted == 0 {
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到该分类下的视频成员。"})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"deleted": deleted, "videoDeleted": videoDeleted})
}

// handleInternalExport 导出 catalog 的全部成员(可选按分类筛选),供"把数据部署到其他地方":
// 返回的每条 item 与导入接口字段兼容(含 status/pinned/rank),把该 JSON 原样 POST 到另一处
// media_server 的 /internal/catalog/import 即可忠实重建分类数据(approved 项一步上线)。
func (s *mediaServer) handleInternalExport(w http.ResponseWriter, r *http.Request) {
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
	if category != "" {
		if _, ok := s.categoriesByID[category]; !ok {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "未知分类:" + category})
			return
		}
	}
	// limit=0 表示不限:一次性导出全部成员。
	members, err := s.catalog.ListMembers(category, status, 0, 0)
	if err != nil {
		s.writeError(w, fmt.Errorf("export members: %w", err))
		return
	}
	items := make([]internalReviewItem, 0, len(members))
	for _, it := range members {
		items = append(items, toInternalReviewItem(it))
	}
	scope := category
	if scope == "" {
		scope = "all"
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"items":      items,
		"count":      len(items),
		"category":   scope,
		"status":     status,
		"exportedAt": time.Now().UTC().Format(time.RFC3339),
	})
}
