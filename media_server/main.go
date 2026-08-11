package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// mediaServer 是纯目录服务:只从 catalog(SQLite)提供 provider 列表、分类列表、
// 视频列表。不调任何上游 API、不做播放地址解析、不做 asset 代理——播放完全由
// App 端根据 provider 自行处理(bilibili 调 B 站 API,youtube 等 future provider 同理)。
type mediaServer struct {
	config         AppConfig
	categories     []ContentCategory
	categoriesByID map[string]ContentCategory
	catalog        *Catalog
	credentials    []ProviderCredential
	crashLimiter   *ipWindowLimiter
	// 凭证端点 per-IP 限流(独立于崩溃上报限流器)。
	credentialsLimiter *ipWindowLimiter
}

// 凭证端点 per-IP 限流:正常 App 每次启动只拉一次凭证,阈值宽松但可挡匿名批量抓取。
const credentialsPerMinuteLimit = 30

// 搜索关键词最大字节数:超长一律拒绝,避免无界 query 驱动全表 LIKE 扫描。
const maxSearchQueryBytes = 128

func main() {
	catalogReview := flag.Bool("catalog-review", false, "审校分类维护数据库的 pending 候选(approve/reject)后退出")
	catalogReviewCategory := flag.String("catalog-review-category", "", "限定只审校某个分类 ID(默认全部)")
	catalogReviewDryRun := flag.Bool("catalog-review-dry-run", false, "只打印审校结果,不写库")
	catalogReviewReviewer := flag.String("catalog-review-reviewer", "", "审校人留痕(默认按模式自动填)")
	catalogConsolidate := flag.Bool("catalog-consolidate", false, "合并 catalog 中分 P 条目(_pN)为单一视频,删除冗余记录后退出")
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
		config:             config,
		categories:         content.Categories,
		categoriesByID:     indexCategories(content.Categories),
		crashLimiter:       newIPWindowLimiter(crashReportPerMinuteLimit, time.Minute),
		credentialsLimiter: newIPWindowLimiter(credentialsPerMinuteLimit, time.Minute),
	}

	if config.CredentialsPath != "" {
		if creds, err := loadCredentials(config.CredentialsPath); err != nil {
			log.Printf("warning: load credentials %q failed: %v", config.CredentialsPath, err)
		} else {
			server.credentials = creds
			log.Printf("loaded %d provider credential(s) from %s", len(creds), config.CredentialsPath)
		}
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

	if *catalogConsolidate {
		if server.catalog == nil {
			log.Fatalf("catalog consolidate requires a catalog db (set MEDIA_SERVER_CATALOG_DB)")
		}
		log.Printf("consolidating page entries in catalog (db=%s)…", config.CatalogDBPath)
		groups, deleted, created, err := server.catalog.ConsolidatePages()
		if err != nil {
			log.Fatalf("catalog consolidate: %v", err)
		}
		log.Printf("consolidation done: %d groups processed, %d _pN records deleted, %d base videos created", groups, deleted, created)
		return
	}

	addr := fmt.Sprintf("%s:%s", config.Host, config.Port)
	srv := &http.Server{
		Addr:              addr,
		Handler:           requestLogMiddleware(server),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		// WriteTimeout 不设:它是「从 request body 读完到写完响应」的硬截止,
		// 对 http.ServeFile 流式发送的大文件下载(/api/app/download)会在慢客户端
		// 下被强行截断。API 路由的超时保护改由 ServeHTTP 里的 TimeoutHandler 精确
		// 施加(下载端点绕过它),避免一刀切。
		IdleTimeout: 120 * time.Second,
	}
	log.Printf("media_server listening on %s", addr)

	// 优雅停机:收到 SIGTERM/SIGINT 后停止接收新连接,给进行中的请求最多
	// 10 秒收尾(否则 compose stop/重启会直接砍断在途请求)。
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	log.Println("media_server shutting down…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("graceful shutdown: %v", err)
	}
}

// apiTimeout 是普通 API 路由的超时上限。http.Server.WriteTimeout 之所以置空,
// 是为了让大文件下载(/api/app/download)不被砍断;这里用 TimeoutHandler 把超时
// 保护精确施加在 API 路由上,下载端点在 ServeHTTP 里优先匹配、绕过此包裹。
const apiTimeout = 60 * time.Second

func (s *mediaServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 大文件下载绕过超时包裹:TimeoutHandler 会缓冲整个响应再写出,对流式发送
	// 的 APK 下载既没必要又浪费内存,且超时会截断传输。优先匹配后直接交给 handler。
	if r.Method == http.MethodGet && r.URL.Path == "/api/app/download" {
		s.handleAppDownload(w, r)
		return
	}
	http.TimeoutHandler(http.HandlerFunc(s.serveAPI), apiTimeout, "服务超时").ServeHTTP(w, r)
}

// serveAPI 处理除大文件下载外的全部路由。由 ServeHTTP 经 TimeoutHandler 包裹,
// 单个 API 请求超过 apiTimeout 即返回 503 并中断,防止慢查询/卡住的 handler 拖垮服务。
func (s *mediaServer) serveAPI(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && (r.URL.Path == "/health" || r.URL.Path == "/api/health"):
		s.writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	case strings.HasPrefix(r.URL.Path, "/internal/"):
		s.handleInternal(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/providers":
		s.handleProviders(w)
	case r.Method == http.MethodGet && r.URL.Path == "/api/providers/credentials":
		s.handleProviderCredentials(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/providers/"):
		s.handleProviderRoute(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/search":
		s.handleSearch(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/app/update":
		s.handleAppUpdate(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/api/crash/report":
		s.handleCrashReport(w, r)
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
	// 目录数据变化低频(审核后更新),60s 缓存显著减少 App 启动/回首页的重复拉取;
	// 凭证等敏感接口不在此列。
	w.Header().Set("Cache-Control", "public, max-age=60")
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

// handleProviderCredentials 返回 admin 配置的 provider 凭证(如 bilibili SESSDATA)。
// App 端拉取后用于请求高分辨率内容。无凭证时返回空数组。
//
// 安全:凭证是共享账号的会话 cookie,泄露即整体轮换。端点要求请求携带与
// MEDIA_SERVER_APP_API_TOKEN 匹配的 X-App-Token(常量时间比较),并叠加 per-IP
// 限流;未配置 token 时一律 401(安全默认,App 端降级为无凭证模式)。
func (s *mediaServer) handleProviderCredentials(w http.ResponseWriter, r *http.Request) {
	if !s.credentialsLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "请求过于频繁,请稍后再试。"})
		return
	}
	expected := s.config.AppAPIToken
	if expected == "" || !constantTimeEqual(r.Header.Get("X-App-Token"), expected) {
		s.writeError(w, &apiError{Status: http.StatusUnauthorized, Code: "UNAUTHORIZED", Message: "凭证接口未授权。"})
		return
	}
	creds := s.credentials
	if creds == nil {
		creds = []ProviderCredential{}
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"credentials": creds})
}

// constantTimeEqual 常量时间比较两个 token,避免时序侧信道。
func constantTimeEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// handleSearch 在已审核(approved)目录数据中按关键词搜索(标题/简介/作者子串,
// 跨分类去重),不调任何上游。App 端目前用本地目录过滤,此端点供未来优化/外部
// 工具使用;query 为空时返回空结果。
func (s *mediaServer) handleSearch(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		s.writeJSON(w, http.StatusOK, SearchResponse{Query: "", Total: 0, Items: []VideoSummary{}})
		return
	}
	// 搜索经三列 lower() LIKE '%q%' 全表扫描,无界 query 会放大计算量。128 字节
	// (UTF-8 中文约 42 字符)足够覆盖真实关键词;超长一律拒绝,避免被滥用拖垮 SQLite 单连接。
	if len(query) > maxSearchQueryBytes {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "QUERY_TOO_LONG", Message: "搜索关键词过长。"})
		return
	}
	page, pageSize, err := paginationFromRequest(r)
	if err != nil {
		s.writeError(w, err)
		return
	}
	if s.catalog == nil {
		s.writeJSON(w, http.StatusOK, SearchResponse{Query: query, Total: 0, Items: []VideoSummary{}})
		return
	}
	items, total, searchErr := s.catalog.SearchApproved(query, strings.TrimSpace(r.URL.Query().Get("provider")), page, pageSize)
	if searchErr != nil {
		log.Printf("search %q: %v", query, searchErr)
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		return
	}
	summaries := make([]VideoSummary, 0, len(items))
	for _, cv := range items {
		summaries = append(summaries, s.summaryFromCatalogVideo(cv))
	}
	s.writeJSON(w, http.StatusOK, SearchResponse{Query: query, Total: total, Items: summaries})
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
	w.Header().Set("Cache-Control", "public, max-age=60")
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
			// catalog 有 approved 数据但 contentCategories.json 未定义该分类:
			// 标题带上 id 便于定位配置缺失,而不是把裸 id 当标题展示。
			items = append(items, PublicCategory{ID: id, Title: "未定义分类 " + id})
		}
	}
	s.writeJSON(w, http.StatusOK, CategoriesResponse{Items: items})
}

// handleProviderCategoryVideos 返回某 provider 某分类下 approved 视频列表(分页)。
func (s *mediaServer) handleProviderCategoryVideos(w http.ResponseWriter, r *http.Request, provider, categoryID string) {
	w.Header().Set("Cache-Control", "public, max-age=60")
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
		// 严格解析:fmt.Sscanf 会宽容接受 "12abc"/"12.5" 之类的脏输入,
		// 用 strconv.Atoi 保证 "12abc" 直接 400。
		p, err := strconv.Atoi(raw)
		if err != nil || p <= 0 || p > 200 {
			return 0, 0, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "page 参数不合法。"}
		}
		page = p
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("pageSize")); raw != "" {
		ps, err := strconv.Atoi(raw)
		if err != nil || ps <= 0 || ps > 40 {
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
