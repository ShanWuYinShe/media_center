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

type mediaServer struct {
	config         AppConfig
	categories     []ContentCategory
	categoriesByID map[string]ContentCategory
	providers      map[string]VideoProvider
	enabledSources []string
	idCodec        *IDCodec
	asset          *AssetSigner
	assetClient    *http.Client
	assetCache     *AssetCache
	deliveryModes  map[string]string
	catalog        *Catalog
	ops            *opTracker
}

func main() {
	catalogSeed := flag.Bool("catalog-seed", false, "把各分类实时聚合结果作为 pending 候选写入分类数据库后退出(供外部工具审校)")
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
	providers, enabledSources, err := newVideoProviders(config, content.Sources)
	if err != nil {
		log.Fatalf("load providers: %v", err)
	}
	server := &mediaServer{
		config:         config,
		categories:     content.Categories,
		categoriesByID: indexCategories(content.Categories),
		providers:      providers,
		enabledSources: enabledSources,
		idCodec:        NewIDCodec(config.SigningKey),
		asset:          NewAssetSigner(config.SigningKey, config.AssetURLTTL),
		assetClient:    newAssetHTTPClient(),
		assetCache:     NewAssetCache(config.AssetCacheDir, config.AssetCacheMax),
		deliveryModes:  sourceDeliveryModes(content.Sources, enabledSources),
		ops:            &opTracker{},
	}

	// 分类维护数据库(SQLite system-of-record):分类浏览直接分页读取 approved 行。
	// 打开失败时降级为 nil,服务返回空分类结果,不影响搜索/详情等实时路径。
	if config.CatalogDBPath != "" {
		if catalog, err := OpenCatalog(config.CatalogDBPath); err != nil {
			log.Printf("warning: open catalog %q failed, category browsing will be empty: %v", config.CatalogDBPath, err)
		} else {
			server.catalog = catalog
			defer catalog.Close()
		}
	}

	// 离线一次性把各分类实时聚合结果写入数据库作为 pending 候选,供外部工具审校。
	if *catalogSeed {
		if server.catalog == nil {
			log.Fatalf("catalog seed requires a catalog db (set MEDIA_SERVER_CATALOG_DB)")
		}
		log.Printf("seeding catalog candidates (db=%s)…", config.CatalogDBPath)
		if err := server.seedCatalog(context.Background()); err != nil {
			log.Fatalf("catalog seed: %v", err)
		}
		return
	}

	// 审校分类维护数据库的 pending 候选:交互式逐条 approve/reject。
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
	log.Printf("media_server listening on %s (sources=%s)", addr, strings.Join(enabledSources, ","))
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
	case r.URL.Path == "/api/asset":
		s.handleAsset(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/categories":
		s.handleCategories(w)
	case r.Method == http.MethodGet && r.URL.Path == "/api/app/update":
		s.handleAppUpdate(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/app/download":
		s.handleAppDownload(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/search":
		s.handleSearch(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/categories/") && strings.HasSuffix(r.URL.Path, "/videos"):
		s.handleCategoryVideos(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/videos/"):
		s.handleVideos(w, r)
	default:
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "请求的资源不存在。",
		})
	}
}

func (s *mediaServer) handleCategories(w http.ResponseWriter) {
	items := make([]PublicCategory, 0, len(s.categories))
	for _, category := range s.categories {
		items = append(items, PublicCategory{
			ID:          category.ID,
			Title:       category.Title,
			Description: category.Description,
		})
	}
	s.writeJSON(w, http.StatusOK, CategoryResponse{Items: items})
}

func (s *mediaServer) handleSearch(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		s.writeError(w, &apiError{
			Status:  http.StatusBadRequest,
			Code:    "BAD_REQUEST",
			Message: "搜索关键词不能为空。",
		})
		return
	}
	page, pageSize, err := paginationFromRequest(r)
	if err != nil {
		s.writeError(w, err)
		return
	}
	resp, searchErr := s.searchProviders(r.Context(), query, page, pageSize)
	if searchErr != nil {
		s.writeError(w, searchErr)
		return
	}
	s.applyDeliveryToSearch(s.assetBaseURL(r), &resp)
	s.writeJSON(w, http.StatusOK, resp)
}

func (s *mediaServer) handleCategoryVideos(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/categories/")
	categoryID := strings.TrimSuffix(path, "/videos")
	categoryID = strings.TrimSuffix(categoryID, "/")
	category, ok := s.categoriesByID[categoryID]
	if !ok {
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "未找到分类。",
		})
		return
	}
	page, pageSize, err := paginationFromRequest(r)
	if err != nil {
		s.writeError(w, err)
		return
	}
	resp, categoryErr := s.categoryVideosPage(r.Context(), category, page, pageSize)
	if categoryErr != nil {
		s.writeError(w, categoryErr)
		return
	}
	s.applyDeliveryToSearch(s.assetBaseURL(r), &resp)
	s.writeJSON(w, http.StatusOK, resp)
}

// categoryVideosPage 直接分页读取 catalog 数据库里的 approved 列表;
// 绝不做实时上游搜索——分类浏览只展示已审核的种子数据。
func (s *mediaServer) categoryVideosPage(
	ctx context.Context,
	category ContentCategory,
	page int,
	pageSize int,
) (SearchResponse, error) {
	if s.catalog != nil {
		items, total, err := s.catalog.ApprovedPage(category.ID, page, pageSize)
		if err == nil && total > 0 {
			summaries := make([]VideoSummary, 0, len(items))
			for _, cv := range items {
				summaries = append(summaries, s.summaryFromCatalogVideo(cv))
			}
			return SearchResponse{
				Category: &PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description},
				Page:     page,
				PageSize: pageSize,
				Total:    total,
				Items:    summaries,
			}, nil
		}
	}
	return SearchResponse{
		Category: &PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description},
		Page:     page,
		PageSize: pageSize,
		Total:    0,
		Items:    []VideoSummary{},
	}, nil
}

func (s *mediaServer) handleVideos(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/videos/")
	if path == "" {
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "未找到视频。",
		})
		return
	}
	if strings.HasSuffix(path, "/playback") {
		videoID := strings.TrimSuffix(path, "/playback")
		videoID = strings.TrimSuffix(videoID, "/")
		s.handlePlayback(w, r, videoID)
		return
	}
	videoID := strings.TrimSuffix(path, "/")
	ref, err := s.idCodec.DecodeVideoRef(videoID)
	if err != nil {
		s.writeError(w, &apiError{
			Status:  http.StatusBadRequest,
			Code:    "BAD_REQUEST",
			Message: "视频 ID 不合法。",
		})
		return
	}
	provider := s.providers[ref.Provider]
	if provider == nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "视频来源不受支持。"})
		return
	}
	details, detailErr := provider.GetDetails(r.Context(), ref.ID, s.idCodec)
	if detailErr != nil {
		s.writeError(w, detailErr)
		return
	}
	s.applyDeliveryToDetailsForProvider(s.assetBaseURL(r), ref.Provider, &details)
	s.writeJSON(w, http.StatusOK, details)
}

func (s *mediaServer) handlePlayback(w http.ResponseWriter, r *http.Request, videoID string) {
	ref, err := s.idCodec.DecodeVideoRef(videoID)
	if err != nil {
		s.writeError(w, &apiError{
			Status:  http.StatusBadRequest,
			Code:    "BAD_REQUEST",
			Message: "视频 ID 不合法。",
		})
		return
	}
	provider := s.providers[ref.Provider]
	if provider == nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "视频来源不受支持。"})
		return
	}
	options, playbackErr := provider.GetPlaybackOptions(r.Context(), ref.ID, s.idCodec)
	if playbackErr != nil {
		s.writeError(w, playbackErr)
		return
	}
	deliveryOverride, deliveryErr := playbackDeliveryOverride(r)
	if deliveryErr != nil {
		s.writeError(w, deliveryErr)
		return
	}
	s.applyDeliveryToOptionsForProviderWithMode(s.assetBaseURL(r), ref.Provider, deliveryOverride, options)
	s.writeJSON(w, http.StatusOK, PlaybackOptionsResponse{Items: options})
}

func (s *mediaServer) searchProviders(ctx context.Context, query string, page int, pageSize int) (SearchResponse, error) {
	var items []VideoSummary
	total := 0
	for _, providerID := range s.enabledSources {
		provider := s.providers[providerID]
		if provider == nil {
			continue
		}
		result, err := provider.Search(ctx, query, page, pageSize, s.idCodec)
		if err != nil {
			return SearchResponse{}, err
		}
		total += result.Total
		items = append(items, result.Items...)
	}
	items = dedupeVideoSummaries(items)
	if total <= 0 {
		total = len(items)
	}
	return SearchResponse{Query: query, Page: page, PageSize: pageSize, Total: total, Items: items}, nil
}

// collectCategoryItems 聚合某分类的粗略候选列表(去重 + 合规过滤,不做相关性筛选/排序),
// 返回完整列表与 total。精准筛选由 admin 页面的人工审核与 AI 模型判断完成。
// 仅 catalog seed 复用它,把粗略搜索结果作为 pending 候选写入分类数据库。
func (s *mediaServer) collectCategoryItems(
	ctx context.Context,
	category ContentCategory,
	targetCount int,
	fetchBudget int,
) ([]VideoSummary, int, error) {
	collected := make([]VideoSummary, 0, targetCount)
	seen := make(map[string]struct{}, targetCount)
	total := 0
	fetches := 0

	// 把 (源 × 搜索词) 组成多个拉取对,按“页优先”交错抓取:先抓所有搜索词的第 1 页,
	// 再抓第 2 页……这样首页就能聚合到各搜索词的多样结果,而不是被单个搜索词占满。
	type fetchPair struct {
		provider     VideoProvider
		query        string
		totalCounted bool
		exhausted    bool
	}
	pairs := make([]*fetchPair, 0)
	for _, providerID := range s.enabledSources {
		provider := s.providers[providerID]
		if provider == nil {
			continue
		}
		for _, providerQuery := range s.categoryQueriesForProvider(category, providerID) {
			pairs = append(pairs, &fetchPair{provider: provider, query: providerQuery})
		}
	}
	// 限制单次请求向上游发起的搜索次数,避免“多搜索词 × 多页 × 多源”放大延迟;
	// 但至少要保证每个 (源 × 搜索词) 对都被抓取一次,否则靠后的搜索词永远拿不到,
	// 多搜索词聚合形同虚设(典型表现:结果寥寥)。
	if fetchBudget < len(pairs) {
		fetchBudget = len(pairs)
	}

collect:
	for providerPage := 1; providerPage <= s.config.MaxCategoryPages; providerPage++ {
		anyActive := false
		for _, pair := range pairs {
			if pair.exhausted {
				continue
			}
			if fetches >= fetchBudget {
				break collect
			}
			result, err := pair.provider.Search(ctx, pair.query, providerPage, 50, s.idCodec)
			fetches++
			if err != nil {
				// 单个搜索词/分页失败不应让整批 500:标记该词不再抓,继续聚合其余结果,
				// 只有在一条结果都没有时才把错误透出。
				if len(collected) > 0 {
					pair.exhausted = true
					continue
				}
				return nil, 0, err
			}
			// result.Total 是该查询的总命中数(每页相同),每个搜索词只累加一次,
			// 避免按页数重复累加导致 Total 被放大数倍。
			if !pair.totalCounted {
				total += result.Total
				pair.totalCounted = true
			}
			if len(result.Items) == 0 {
				pair.exhausted = true
				continue
			}
			anyActive = true
			for _, item := range result.Items {
				if _, ok := seen[item.ID]; ok {
					continue
				}
				seen[item.ID] = struct{}{}
				// 合规过滤(成人/色情内容)是硬性要求,与“分类相关性筛选”无关,予以保留。
				if isDisallowedContent(item) {
					continue
				}
				collected = append(collected, item)
			}
			if len(collected) >= targetCount {
				break collect
			}
		}
		if !anyActive {
			break
		}
	}
	total = max(total, len(collected))
	return collected, total, nil
}

// categoryQueriesForProvider 返回某个源在聚合该分类时使用的搜索词列表。
// 优先用 sourceQueries 里的显式单条覆盖(保持旧配置兼容);否则用分类的多搜索词。
func (s *mediaServer) categoryQueriesForProvider(category ContentCategory, providerID string) []string {
	if q, ok := category.SourceQueries[providerID]; ok {
		if trimmed := strings.TrimSpace(q); trimmed != "" {
			return []string{trimmed}
		}
	}
	if len(category.Queries) > 0 {
		return category.Queries
	}
	return []string{category.Query}
}

func dedupeVideoSummaries(items []VideoSummary) []VideoSummary {
	seen := make(map[string]struct{}, len(items))
	out := make([]VideoSummary, 0, len(items))
	for _, item := range items {
		if _, ok := seen[item.ID]; ok {
			continue
		}
		seen[item.ID] = struct{}{}
		out = append(out, item)
	}
	return out
}

func paginationFromRequest(r *http.Request) (int, int, *apiError) {
	page := 1
	pageSize := 20
	if raw := strings.TrimSpace(r.URL.Query().Get("page")); raw != "" {
		if _, err := fmt.Sscanf(raw, "%d", &page); err != nil || page <= 0 || page > 200 {
			return 0, 0, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "page 参数不合法。"}
		}
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("pageSize")); raw != "" {
		if _, err := fmt.Sscanf(raw, "%d", &pageSize); err != nil || pageSize <= 0 || pageSize > 40 {
			return 0, 0, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "pageSize 参数不合法。"}
		}
	}
	return page, pageSize, nil
}

func playbackDeliveryOverride(r *http.Request) (string, *apiError) {
	raw := strings.TrimSpace(r.URL.Query().Get("delivery"))
	if raw == "" {
		return "", nil
	}
	if raw == DeliveryProxy {
		return raw, nil
	}
	return "", &apiError{
		Status:  http.StatusBadRequest,
		Code:    "BAD_REQUEST",
		Message: "delivery 参数不合法。",
	}
}

func (s *mediaServer) writeJSON(w http.ResponseWriter, status int, payload any) {
	body, err := json.Marshal(payload)
	if err != nil {
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
		apiErr = &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "服务端内部错误。",
		}
	}
	s.writeJSON(w, apiErr.Status, apiErrorEnvelope{
		Error: apiErrorBody{
			Code:    apiErr.Code,
			Message: apiErr.Message,
		},
	})
}

func indexCategories(categories []ContentCategory) map[string]ContentCategory {
	out := make(map[string]ContentCategory, len(categories))
	for _, category := range categories {
		out[category.ID] = category
	}
	return out
}

// assetBaseURL 解析对外可见的 API 基址，用于拼接 /asset 代理地址。
// 优先用 MEDIA_SERVER_PUBLIC_URL；否则按请求(含反代头)推导。
func (s *mediaServer) assetBaseURL(r *http.Request) string {
	if s.config.PublicBaseURL != "" {
		return s.config.PublicBaseURL
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if proto := firstForwardedValue(r.Header.Get("X-Forwarded-Proto")); proto != "" {
		scheme = proto
	}
	host := r.Host
	if forwarded := firstForwardedValue(r.Header.Get("X-Forwarded-Host")); forwarded != "" {
		host = forwarded
	}
	prefix := firstForwardedValue(r.Header.Get("X-Forwarded-Prefix"))
	if prefix == "" {
		prefix = "/api"
	}
	if !strings.HasPrefix(prefix, "/") {
		prefix = "/" + prefix
	}
	return scheme + "://" + host + strings.TrimRight(prefix, "/")
}

func firstForwardedValue(raw string) string {
	if raw == "" {
		return ""
	}
	return strings.TrimSpace(strings.Split(raw, ",")[0])
}

// deliver 决定单个资源的下发方式：
//   - direct：原样返回上游 URL + companion headers，由 App 直连。
//   - proxy：返回 /asset 签名代理地址，上游请求头由服务端回源时附加，不下发。
func (s *mediaServer) deliver(providerID, base, rawURL string, headers map[string]string) (string, map[string]string, string) {
	return s.deliverWithMode(providerID, base, rawURL, headers, "")
}

func (s *mediaServer) deliverThumbnail(providerID, base, rawURL string, headers map[string]string) (string, map[string]string, string) {
	// 缩略图体积小,统一走签名 asset 代理,避免 Android 客户端直连上游时被老旧
	// 证书链、Referer/UA 差异或区域网络策略影响;播放流仍按来源走 direct/proxy。
	if strings.TrimSpace(rawURL) == "" {
		return rawURL, headers, DeliveryDirect
	}
	return s.deliverWithMode(providerID, base, rawURL, headers, DeliveryProxy)
}

func (s *mediaServer) deliverWithMode(
	providerID,
	base,
	rawURL string,
	headers map[string]string,
	mode string,
) (string, map[string]string, string) {
	if mode == "" {
		mode = s.deliveryModeForProvider(providerID)
	}
	if mode == DeliveryProxy && strings.TrimSpace(rawURL) != "" {
		return s.asset.ProxyURL(base, rawURL, headers), nil, DeliveryProxy
	}
	return rawURL, headers, DeliveryDirect
}

func (s *mediaServer) deliveryModeForProvider(providerID string) string {
	if mode := s.deliveryModes[providerID]; mode != "" {
		return mode
	}
	return sourceDeliveryMode(nil, providerID)
}

func (s *mediaServer) providerFromVideoID(videoID string) string {
	ref, err := s.idCodec.DecodeVideoRef(videoID)
	if err != nil {
		return ""
	}
	return ref.Provider
}

func (s *mediaServer) applyDeliveryToSummary(base string, v *VideoSummary) {
	providerID := s.providerFromVideoID(v.ID)
	v.ThumbnailURL, v.ThumbnailHeaders, v.ThumbnailDelivery = s.deliverThumbnail(providerID, base, v.ThumbnailURL, v.ThumbnailHeaders)
}

func (s *mediaServer) applyDeliveryToOptionsForProvider(base, providerID string, options []PlaybackOption) {
	s.applyDeliveryToOptionsForProviderWithMode(base, providerID, "", options)
}

func (s *mediaServer) applyDeliveryToOptionsForProviderWithMode(
	base,
	providerID,
	mode string,
	options []PlaybackOption,
) {
	for i := range options {
		options[i].URL, options[i].Headers, options[i].Delivery = s.deliverWithMode(
			providerID,
			base,
			options[i].URL,
			options[i].Headers,
			mode,
		)
	}
}

func (s *mediaServer) applyDeliveryToSearch(base string, resp *SearchResponse) {
	for i := range resp.Items {
		s.applyDeliveryToSummary(base, &resp.Items[i])
	}
}

func (s *mediaServer) applyDeliveryToDetailsForProvider(base, providerID string, details *VideoDetails) {
	details.ThumbnailURL, details.ThumbnailHeaders, details.ThumbnailDelivery = s.deliverThumbnail(providerID, base, details.ThumbnailURL, details.ThumbnailHeaders)
	s.applyDeliveryToOptionsForProvider(base, providerID, details.PlaybackOptions)
}

// isDisallowedContent 判断候选是否命中成人/色情等不合规内容黑名单。
// 这是合规硬性要求(只处理公开、合规内容),与“分类相关性筛选”无关,
// 因此在粗略搜索阶段保留;其余精准筛选交由 admin 页面的人工/AI 审核。
func isDisallowedContent(item VideoSummary) bool {
	text := strings.ToLower(strings.Join([]string{
		item.Title,
		item.Description,
		strings.Join(item.Creators, " "),
	}, " "))
	return containsAny(text, adultContentExcludes)
}

// adultContentExcludes 是跨分类的成人/色情内容黑名单(全部小写匹配)。
// 只用足够明确、不会误伤主流影视标题的词,避免把正常内容过滤掉。
var adultContentExcludes = []string{
	"三级片", "三級片", "情色", "色情", "成人电影", "成人影片", "限制级", "限制級",
	"18禁", "r18", "里番", "裏番", "无码", "無碼", "黄片", "黄色电影", "伦理片", "倫理片",
	"性爱", "性愛", "激情戏", "激情戲", "床戏", "床戲", "福利姬", "擦边", "擦邊",
}

func containsAny(text string, terms []string) bool {
	for _, term := range terms {
		normalized := strings.ToLower(strings.TrimSpace(term))
		if normalized != "" && strings.Contains(text, normalized) {
			return true
		}
	}
	return false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
