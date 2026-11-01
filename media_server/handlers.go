package main

import (
	"crypto/subtle"
	"log"
	"net/http"
	"slices"
	"strconv"
	"strings"
)

// handleProviders 返回 App 可选的视频源:
//   - catalog 中有 approved 视频的 provider;
//   - 配置了 vod_extractor 时追加 youtube——其内容只走实时搜索,永不写 catalog,
//     若只按 catalog 推导,App 将永远无法切换到 youtube 源。
func (s *mediaServer) handleProviders(w http.ResponseWriter) {
	// 目录数据变化低频(审核后更新),60s 缓存显著减少 App 启动/回首页的重复拉取;
	// 凭证等敏感接口不在此列。
	w.Header().Set("Cache-Control", "public, max-age=60")
	items := []ProviderInfo{}
	if s.catalog != nil {
		providerIDs, err := s.catalog.DistinctProviders()
		if err != nil {
			s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
			log.Printf("list providers: %v", err)
			return
		}
		for _, id := range providerIDs {
			items = append(items, ProviderInfo{ID: id, Name: providerDisplayName(id)})
		}
	}
	if s.config.VODExtractorURL != "" && !slices.ContainsFunc(items, func(p ProviderInfo) bool { return p.ID == "youtube" }) {
		items = append(items, ProviderInfo{ID: "youtube", Name: providerDisplayName("youtube")})
	}
	s.writeJSON(w, http.StatusOK, ProvidersResponse{Items: items})
}

// handleProviderCredentials 返回 admin 配置的 provider 凭证(如 bilibili SESSDATA)。
// App 端拉取后用于请求高分辨率内容。无凭证时返回空数组。
//
// 安全:凭证是共享账号的会话 cookie,泄露即整体轮换。端点要求请求携带与
// MEDIA_SERVER_APP_API_TOKEN 匹配的 X-App-Token(常量时间比较),并叠加 per-IP
// 限流;未配置 token 时一律 401(安全默认,App 端降级为无凭证模式)。
// 鉴权先于限流:401 不消耗配额,避免无 token 请求烧光合法 App 的配额。
func (s *mediaServer) handleProviderCredentials(w http.ResponseWriter, r *http.Request) {
	expected := s.config.AppAPIToken
	if expected == "" || !constantTimeEqual(r.Header.Get("X-App-Token"), expected) {
		s.writeError(w, &apiError{Status: http.StatusUnauthorized, Code: "UNAUTHORIZED", Message: "凭证接口未授权。"})
		return
	}
	if !s.credentialsLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "请求过于频繁,请稍后再试。"})
		return
	}
	creds := s.currentCredentials()
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
// 跨分类去重),不调任何上游。App 的目录搜索直接消费此端点;query 为空时
// 返回空结果。
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
	// 本端点匿名可达且是最重的公开查询:参数校验(廉价 400)之后,先限流、
	// 再过并发闸门,重查询不消耗排队位。
	limiter := s.searchLimiter
	if limiter == nil {
		limiter = defaultSearchLimiter
	}
	if !limiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "请求过于频繁,请稍后再试。"})
		return
	}
	gate := s.searchGate
	if gate == nil {
		gate = defaultSearchGate
	}
	select {
	case gate <- struct{}{}:
		defer func() { <-gate }()
	default:
		// 满载:SQLite 单连接下排队只会拖垮目录浏览主链路,快速 503 让
		// 恶意并发拿不到连接等待队列。
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "SERVICE_BUSY", Message: "搜索繁忙,请稍后再试。"})
		return
	}
	// 目录数据变化低频,短缓存既省重复扫描又与限流预算互补(结果一致性
	// 延迟 30s 对搜索场景无感;providers 用 60s,搜索更新感知更敏感故减半)。
	// 429/503/400 不带缓存头,只有成功响应可缓存。
	w.Header().Set("Cache-Control", "public, max-age=30")
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
	s.writeJSON(w, http.StatusOK, SearchResponse{Query: query, Total: int64(total), Items: summaries})
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
	case len(parts) == 4 && parts[1] == "videos" && parts[3] == "collection":
		s.handleVideoCollection(w, r, provider, parts[2])
	// youtube 实时端点:搜索与播放解析(均代理 vod_extractor),见 youtube.go。
	case provider == "youtube" && len(parts) == 2 && parts[1] == "search":
		s.handleYouTubeSearch(w, r)
	case provider == "youtube" && len(parts) == 2 && parts[1] == "resolve":
		s.handleYouTubeResolve(w, r)
	default:
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "请求的资源不存在。"})
	}
}

// handleVideoCollection 返回某视频所在合集的“已审核剧集”播放队列。
// 未入合集/合集内没有已审核剧集时返回 collection=null + 空 items(200),
// 让 App 静默退化为不连播,而不是把正常视频当成错误。
func (s *mediaServer) handleVideoCollection(w http.ResponseWriter, r *http.Request, provider, videoID string) {
	w.Header().Set("Cache-Control", "public, max-age=60")
	resp := VideoCollectionResponse{CurrentIndex: -1, Items: []VideoSummary{}}
	videoID = strings.TrimSpace(videoID)
	if s.catalog == nil || videoID == "" {
		s.writeJSON(w, http.StatusOK, resp)
		return
	}
	queue, ok, err := s.catalog.CollectionForVideo(provider, videoID)
	if err != nil {
		log.Printf("collection for video provider=%s id=%s: %v", provider, videoID, err)
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		return
	}
	if !ok {
		s.writeJSON(w, http.StatusOK, resp)
		return
	}
	resp.Collection = &VideoCollectionInfo{ID: queue.ID, Title: queue.Title, Total: len(queue.Items)}
	resp.CurrentIndex = queue.CurrentIndex
	for _, cv := range queue.Items {
		resp.Items = append(resp.Items, s.summaryFromCatalogVideo(cv))
	}
	s.writeJSON(w, http.StatusOK, resp)
}

// handleProviderCategories 返回某 provider 有 approved 视频的分类列表。
func (s *mediaServer) handleProviderCategories(w http.ResponseWriter, r *http.Request, provider string) {
	w.Header().Set("Cache-Control", "public, max-age=60")
	// YouTube 内容只存在于实时搜索,不在 catalog;分类由服务端内置映射。
	if provider == "youtube" {
		items := []PublicCategory{}
		if s.youtubeRealtimeEnabled() {
			items = publicYouTubeCategories()
		}
		s.writeJSON(w, http.StatusOK, CategoriesResponse{Items: items})
		return
	}
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
	// 分类顺序以 contentCategories.json 的编排顺序为准(运营可调),而不是
	// catalog 里的 category_id 字典序——否则首页分类顺序完全由 id 拼写决定。
	present := make(map[string]struct{}, len(categoryIDs))
	for _, id := range categoryIDs {
		present[id] = struct{}{}
	}
	items := make([]PublicCategory, 0, len(categoryIDs))
	added := make(map[string]struct{}, len(categoryIDs))
	for _, cat := range s.categories {
		if _, ok := present[cat.ID]; !ok {
			continue
		}
		items = append(items, PublicCategory{ID: cat.ID, Title: cat.Title, Description: cat.Description})
		added[cat.ID] = struct{}{}
	}
	// 兜底:只出现在 categoriesByID、没进入 categories 切片的分类(调用方
	// 构造不完整/配置源不一致)仍按定义输出;两边都没有的才算未定义分类,
	// 标题带上 id 便于定位配置缺失。
	for _, id := range categoryIDs {
		if _, ok := added[id]; ok {
			continue
		}
		if cat, ok := s.categoriesByID[id]; ok {
			items = append(items, PublicCategory{ID: cat.ID, Title: cat.Title, Description: cat.Description})
			continue
		}
		items = append(items, PublicCategory{ID: id, Title: "未定义分类 " + id})
	}
	s.writeJSON(w, http.StatusOK, CategoriesResponse{Items: items})
}

// handleProviderCategoryVideos 返回某 provider 某分类下 approved 视频列表(分页)。
func (s *mediaServer) handleProviderCategoryVideos(w http.ResponseWriter, r *http.Request, provider, categoryID string) {
	// YouTube 分类不走 catalog,由实时 ytsearch 提供;鉴权/限流在 handler 内处理。
	if provider == "youtube" {
		s.handleYouTubeCategoryVideos(w, r, categoryID)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=60")
	page, pageSize, err := paginationFromRequest(r)
	if err != nil {
		s.writeError(w, err)
		return
	}
	bounds, err := durationBoundsFromRequest(r)
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
	items, total, catErr := s.catalog.ApprovedPageForProviderFiltered(provider, categoryID, page, pageSize, bounds)
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

// durationBoundsFromRequest 解析分类列表的时长筛选参数(秒,含边界)。
// 缺省/0 表示该侧不过滤;两端都提供时必须 min<=max。非法值一律 400,
// 不让脏参数退化成"静默忽略"导致 UI 显示与预期档位不符。
func durationBoundsFromRequest(r *http.Request) (DurationBounds, *apiError) {
	var bounds DurationBounds
	for _, param := range []struct {
		name string
		dst  *int64
	}{
		{"minDurationSeconds", &bounds.MinSec},
		{"maxDurationSeconds", &bounds.MaxSec},
	} {
		raw := strings.TrimSpace(r.URL.Query().Get(param.name))
		if raw == "" {
			continue
		}
		value, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || value < 0 {
			return DurationBounds{}, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: param.name + " 参数不合法。"}
		}
		*param.dst = value
	}
	if bounds.MinSec > 0 && bounds.MaxSec > 0 && bounds.MinSec > bounds.MaxSec {
		return DurationBounds{}, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "时长筛选区间不合法。"}
	}
	return bounds, nil
}
