package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"strings"
)

// mediaServer 是纯目录服务:只从 catalog(SQLite)提供 provider 列表、分类列表、
// 视频列表。不调任何上游 API、不做播放地址解析、不做 asset 代理——播放完全由
// App 端根据 provider 自行处理(bilibili 调 B 站 API,youtube 等 future provider 同理)。
type mediaServer struct {
	config         AppConfig
	categories     []ContentCategory
	categoriesByID map[string]ContentCategory
	catalog        *Catalog
}

func main() {
	catalogReview := flag.Bool("catalog-review", false, "审校分类维护数据库的 pending 候选(approve/reject)后退出")
	catalogReviewCategory := flag.String("catalog-review-category", "", "限定只审校某个分类 ID(默认全部)")
	catalogReviewDryRun := flag.Bool("catalog-review-dry-run", false, "只打印审校结果,不写库")
	catalogReviewReviewer := flag.String("catalog-review-reviewer", "", "审校人留痕(默认按模式自动填)")
	flag.Parse()

	config, err := loadAppConfig()
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	content, err := loadContentConfig(config.CategoriesPath)
	if err != nil {
		log.Fatalf("load categories: %v", err)
	}
	server := &mediaServer{
		config:         config,
		categories:     content.Categories,
		categoriesByID: indexCategories(content.Categories),
	}

	if config.CatalogDBPath != "" {
		if catalog, err := OpenCatalog(config.CatalogDBPath); err != nil {
			log.Printf("warning: open catalog %q failed: %v", config.CatalogDBPath, err)
		} else {
			server.catalog = catalog
			defer catalog.Close()
		}
	}

	if *catalogReview {
		if server.catalog == nil {
			log.Fatalf("catalog review requires a catalog db (set MEDIA_SERVER_CATALOG_DB)")
		}
		opts := ReviewOptions{
			CategoryID: *catalogReviewCategory,
			DryRun:     *catalogReviewDryRun,
			Reviewer:   *catalogReviewReviewer,
		}
		log.Printf("reviewing catalog pending (db=%s, category=%q, dry-run=%t)…",
			config.CatalogDBPath, opts.CategoryID, opts.DryRun)
		if err := server.runCatalogReview(context.Background(), opts); err != nil {
			log.Fatalf("catalog review: %v", err)
		}
		return
	}

	addr := fmt.Sprintf("%s:%s", config.Host, config.Port)
	log.Printf("media_server listening on %s", addr)
	if err := http.ListenAndServe(addr, server); err != nil {
		log.Fatal(err)
	}
}

func (s *mediaServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && (r.URL.Path == "/health" || r.URL.Path == "/api/health"):
		s.writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	case strings.HasPrefix(r.URL.Path, "/internal/"):
		s.handleInternal(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/providers":
		s.handleProviders(w)
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/providers/"):
		s.handleProviderRoute(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/app/update":
		s.handleAppUpdate(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/app/download":
		s.handleAppDownload(w, r)
	default:
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "请求的资源不存在。",
		})
	}
}

// handleProviders 返回 catalog 中有 approved 视频的所有 provider。
func (s *mediaServer) handleProviders(w http.ResponseWriter) {
	if s.catalog == nil {
		s.writeJSON(w, http.StatusOK, ProvidersResponse{Items: []ProviderInfo{}})
		return
	}
	providerIDs, err := s.catalog.DistinctProviders()
	if err != nil {
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		log.Printf("list providers: %v", err)
		return
	}
	items := make([]ProviderInfo, 0, len(providerIDs))
	for _, id := range providerIDs {
		items = append(items, ProviderInfo{ID: id, Name: providerDisplayName(id)})
	}
	s.writeJSON(w, http.StatusOK, ProvidersResponse{Items: items})
}

// handleProviderRoute 按 /api/providers/{provider}/... 后续路径分发。
func (s *mediaServer) handleProviderRoute(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/providers/")
	parts := strings.Split(strings.TrimSuffix(rest, "/"), "/")
	// parts[0] = {provider}
	if len(parts) < 2 || parts[0] == "" {
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "请求的资源不存在。"})
		return
	}
	provider := parts[0]
	switch {
	case len(parts) == 2 && parts[1] == "categories":
		s.handleProviderCategories(w, r, provider)
	case len(parts) == 4 && parts[1] == "categories" && parts[3] == "videos":
		s.handleProviderCategoryVideos(w, r, provider, parts[2])
	default:
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "请求的资源不存在。"})
	}
}

// handleProviderCategories 返回某 provider 有 approved 视频的分类列表。
func (s *mediaServer) handleProviderCategories(w http.ResponseWriter, r *http.Request, provider string) {
	if s.catalog == nil {
		s.writeJSON(w, http.StatusOK, CategoriesResponse{Items: []PublicCategory{}})
		return
	}
	categoryIDs, err := s.catalog.CategoriesForProvider(provider)
	if err != nil {
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		log.Printf("list categories for provider %s: %v", provider, err)
		return
	}
	items := make([]PublicCategory, 0, len(categoryIDs))
	for _, id := range categoryIDs {
		if cat, ok := s.categoriesByID[id]; ok {
			items = append(items, PublicCategory{ID: cat.ID, Title: cat.Title, Description: cat.Description})
		} else {
			items = append(items, PublicCategory{ID: id, Title: id})
		}
	}
	s.writeJSON(w, http.StatusOK, CategoriesResponse{Items: items})
}

// handleProviderCategoryVideos 返回某 provider 某分类下 approved 视频列表(分页)。
func (s *mediaServer) handleProviderCategoryVideos(w http.ResponseWriter, r *http.Request, provider, categoryID string) {
	page, pageSize, err := paginationFromRequest(r)
	if err != nil {
		s.writeError(w, err)
		return
	}
	category, ok := s.categoriesByID[categoryID]
	if !ok {
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到分类。"})
		return
	}
	if s.catalog == nil {
		s.writeJSON(w, http.StatusOK, VideosResponse{
			Category: &PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description},
			Page:     page, PageSize: pageSize, Total: 0, Items: []VideoSummary{},
		})
		return
	}
	items, total, catErr := s.catalog.ApprovedPageForProvider(provider, categoryID, page, pageSize)
	if catErr != nil {
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		log.Printf("list videos provider=%s category=%s: %v", provider, categoryID, catErr)
		return
	}
	summaries := make([]VideoSummary, 0, len(items))
	for _, cv := range items {
		summaries = append(summaries, s.summaryFromCatalogVideo(cv))
	}
	s.writeJSON(w, http.StatusOK, VideosResponse{
		Category: &PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description},
		Page:     page, PageSize: pageSize, Total: total, Items: summaries,
	})
}

func paginationFromRequest(r *http.Request) (int, int, *apiError) {
	page := 1
	pageSize := 20
	if raw := strings.TrimSpace(r.URL.Query().Get("page")); raw != "" {
		var p int
		if _, err := fmt.Sscanf(raw, "%d", &p); err != nil || p <= 0 || p > 200 {
			return 0, 0, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "page 参数不合法。"}
		}
		page = p
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("pageSize")); raw != "" {
		var ps int
		if _, err := fmt.Sscanf(raw, "%d", &ps); err != nil || ps <= 0 || ps > 40 {
			return 0, 0, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "pageSize 参数不合法。"}
		}
		pageSize = ps
	}
	return page, pageSize, nil
}

func (s *mediaServer) writeJSON(w http.ResponseWriter, status int, payload any) {
	body, err := json.Marshal(payload)
	if err != nil {
		log.Printf("writeJSON marshal: %v", err)
		s.writeError(w, &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "服务端内部错误。",
		})
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func (s *mediaServer) writeError(w http.ResponseWriter, err error) {
	var apiErr *apiError
	if !errors.As(err, &apiErr) {
		log.Printf("unhandled error: %v", err)
		apiErr = &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"}
	}
	s.writeJSON(w, apiErr.Status, apiErrorEnvelope{Error: apiErrorBody{Code: apiErr.Code, Message: apiErr.Message}})
}

func indexCategories(categories []ContentCategory) map[string]ContentCategory {
	out := make(map[string]ContentCategory, len(categories))
	for _, category := range categories {
		out[category.ID] = category
	}
	return out
}
