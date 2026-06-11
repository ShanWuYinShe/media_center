package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strings"
)

type mediaServer struct {
	config         AppConfig
	categories     []ContentCategory
	categoriesByID map[string]ContentCategory
	bilibili       *BilibiliClient
	idCodec        *IDCodec
	asset          *AssetSigner
	assetClient    *http.Client
	deliveryMode   string
}

func main() {
	config, err := loadAppConfig()
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	categories, sources, err := loadCategories(config.CategoriesPath)
	if err != nil {
		log.Fatalf("load categories: %v", err)
	}
	server := &mediaServer{
		config:         config,
		categories:     categories,
		categoriesByID: indexCategories(categories),
		bilibili:       NewBilibiliClient(config.ProviderTimeout),
		idCodec:        NewIDCodec(config.SigningKey),
		asset:          NewAssetSigner(config.SigningKey, config.AssetURLTTL),
		assetClient:    newAssetHTTPClient(),
		deliveryMode:   sourceDeliveryMode(sources, "bilibili"),
	}
	addr := fmt.Sprintf("%s:%s", config.Host, config.Port)
	log.Printf("media_server listening on %s (bilibili delivery=%s)", addr, server.deliveryMode)
	if err := http.ListenAndServe(addr, server); err != nil {
		log.Fatal(err)
	}
}

func (s *mediaServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && (r.URL.Path == "/health" || r.URL.Path == "/api/health"):
		s.writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	case r.URL.Path == "/api/asset":
		s.handleAsset(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/categories":
		s.handleCategories(w)
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
	resp, searchErr := s.bilibili.Search(r.Context(), query, page, pageSize, s.idCodec)
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
	resp, categoryErr := s.collectCategoryPage(r.Context(), category, page, pageSize)
	if categoryErr != nil {
		s.writeError(w, categoryErr)
		return
	}
	s.applyDeliveryToSearch(s.assetBaseURL(r), &resp)
	s.writeJSON(w, http.StatusOK, resp)
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
	bvid, err := s.idCodec.DecodeVideoID(videoID)
	if err != nil {
		s.writeError(w, &apiError{
			Status:  http.StatusBadRequest,
			Code:    "BAD_REQUEST",
			Message: "视频 ID 不合法。",
		})
		return
	}
	details, detailErr := s.bilibili.GetDetails(r.Context(), bvid, s.idCodec)
	if detailErr != nil {
		s.writeError(w, detailErr)
		return
	}
	s.applyDeliveryToDetails(s.assetBaseURL(r), &details)
	s.writeJSON(w, http.StatusOK, details)
}

func (s *mediaServer) handlePlayback(w http.ResponseWriter, r *http.Request, videoID string) {
	bvid, err := s.idCodec.DecodeVideoID(videoID)
	if err != nil {
		s.writeError(w, &apiError{
			Status:  http.StatusBadRequest,
			Code:    "BAD_REQUEST",
			Message: "视频 ID 不合法。",
		})
		return
	}
	options, playbackErr := s.bilibili.GetPlaybackOptions(r.Context(), bvid, s.idCodec)
	if playbackErr != nil {
		s.writeError(w, playbackErr)
		return
	}
	s.applyDeliveryToOptions(s.assetBaseURL(r), options)
	s.writeJSON(w, http.StatusOK, PlaybackOptionsResponse{Items: options})
}

func (s *mediaServer) collectCategoryPage(
	ctx context.Context,
	category ContentCategory,
	page int,
	pageSize int,
) (SearchResponse, error) {
	targetCount := page * pageSize
	providerQuery := category.Query
	if query, ok := category.SourceQueries["bilibili"]; ok && strings.TrimSpace(query) != "" {
		providerQuery = strings.TrimSpace(query)
	}
	type scoredItem struct {
		item  VideoSummary
		score int
		order int
	}
	collected := make([]scoredItem, 0, targetCount)
	seen := make(map[string]struct{}, targetCount)
	total := 0
	for providerPage := 1; providerPage <= s.config.MaxCategoryPages; providerPage++ {
		result, err := s.bilibili.Search(ctx, providerQuery, providerPage, 50, s.idCodec)
		if err != nil {
			return SearchResponse{}, err
		}
		total = max(total, result.Total)
		if len(result.Items) == 0 {
			break
		}
		for _, item := range result.Items {
			if _, ok := seen[item.ID]; ok {
				continue
			}
			seen[item.ID] = struct{}{}
			score := relevanceScore(item, category)
			if score < 0 {
				continue
			}
			collected = append(collected, scoredItem{
				item:  item,
				score: score,
				order: len(collected),
			})
		}
		if len(collected) >= targetCount {
			break
		}
	}
	sort.SliceStable(collected, func(i, j int) bool {
		if collected[i].score == collected[j].score {
			return collected[i].order < collected[j].order
		}
		return collected[i].score > collected[j].score
	})
	items := make([]VideoSummary, 0, len(collected))
	for _, item := range collected {
		items = append(items, item.item)
	}
	start := (page - 1) * pageSize
	if start > len(items) {
		start = len(items)
	}
	end := min(start+pageSize, len(items))
	total = max(total, len(items))
	return SearchResponse{
		Query:    "",
		Category: &PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description},
		Page:     page,
		PageSize: pageSize,
		Total:    total,
		Items:    items[start:end],
	}, nil
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
	return scheme + "://" + host + "/api"
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
func (s *mediaServer) deliver(base, rawURL string, headers map[string]string) (string, map[string]string, string) {
	if s.deliveryMode == DeliveryProxy && strings.TrimSpace(rawURL) != "" {
		return s.asset.ProxyURL(base, rawURL, headers), nil, DeliveryProxy
	}
	return rawURL, headers, DeliveryDirect
}

func (s *mediaServer) applyDeliveryToSummary(base string, v *VideoSummary) {
	v.ThumbnailURL, v.ThumbnailHeaders, v.ThumbnailDelivery = s.deliver(base, v.ThumbnailURL, v.ThumbnailHeaders)
}

func (s *mediaServer) applyDeliveryToOptions(base string, options []PlaybackOption) {
	for i := range options {
		options[i].URL, options[i].Headers, options[i].Delivery = s.deliver(base, options[i].URL, options[i].Headers)
	}
}

func (s *mediaServer) applyDeliveryToSearch(base string, resp *SearchResponse) {
	for i := range resp.Items {
		s.applyDeliveryToSummary(base, &resp.Items[i])
	}
}

func (s *mediaServer) applyDeliveryToDetails(base string, details *VideoDetails) {
	details.ThumbnailURL, details.ThumbnailHeaders, details.ThumbnailDelivery = s.deliver(base, details.ThumbnailURL, details.ThumbnailHeaders)
	s.applyDeliveryToOptions(base, details.PlaybackOptions)
}

func relevanceScore(item VideoSummary, category ContentCategory) int {
	text := strings.ToLower(strings.Join([]string{
		item.Title,
		item.Description,
		strings.Join(item.Creators, " "),
	}, " "))
	if containsAny(text, category.Excludes) {
		return -1
	}
	if category.ID != "dota-zone" && item.DurationSeconds > 0 && item.DurationSeconds < 20*60 {
		return -1
	}
	keywordScore := 0
	for index, keyword := range category.Keywords {
		normalized := strings.ToLower(strings.TrimSpace(keyword))
		if normalized == "" || !strings.Contains(text, normalized) {
			continue
		}
		keywordScore += max(1, len(category.Keywords)-index)
	}
	switch category.ID {
	case "dota-zone":
		switch {
		case strings.Contains(text, "dota1"), strings.Contains(text, "dota 1"), strings.Contains(text, "魔兽争霸"), strings.Contains(text, "allstars"):
			return keywordScore + 30
		case strings.Contains(text, "dota"), strings.Contains(text, "dota2"), strings.Contains(text, "ti"):
			return keywordScore + 5
		default:
			return -1
		}
	case "movies":
		if keywordScore <= 0 || !(strings.Contains(text, "完整版") || strings.Contains(text, "全片") || strings.Contains(text, "正片")) {
			return -1
		}
	case "tv-series":
		if keywordScore <= 0 || !tvSeriesEpisodePattern.MatchString(text) && !strings.Contains(text, "全集") && !strings.Contains(text, "连续剧") && !strings.Contains(text, "剧集") {
			return -1
		}
	case "documentaries":
		if keywordScore <= 0 || !strings.Contains(text, "纪录片") {
			return -1
		}
	case "red-classic":
		if keywordScore <= 0 || !containsAny(text, []string{
			"红色", "长征", "抗战", "抗日", "解放", "解放战争", "革命", "红军", "战争", "抗美援朝",
			"志愿军", "八路军", "新四军", "游击", "地道战", "地雷战", "上甘岭", "英雄儿女",
			"闪闪的红星", "铁道游击队", "董存瑞", "狼牙山", "小兵张嘎", "南征北战", "红色娘子军",
			"智取威虎山", "林海雪原", "老电影",
		}) {
			return -1
		}
	default:
		if keywordScore <= 0 {
			return -1
		}
	}
	return keywordScore
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
