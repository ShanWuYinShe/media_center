package main

import (
	"log"
	"net/http"
	"strings"
)

// youtubeCategory 是 YouTube 实时源的内置分类:YouTube 内容不落 catalog,
// 分类由服务端把稳定的搜索词映射成实时 ytsearch,App 仍按统一目录接口消费。
type youtubeCategory struct {
	ID          string
	Title       string
	Description string
	Query       string
}

var defaultYouTubeCategories = []youtubeCategory{
	{ID: "youtube-music", Title: "音乐", Description: "音乐、现场与歌单", Query: "音乐 现场 歌单"},
	{ID: "youtube-tech", Title: "科技", Description: "数码评测与科技资讯", Query: "科技 数码 评测"},
	{ID: "youtube-gaming", Title: "游戏", Description: "游戏实况与赛事", Query: "游戏 实况 解说"},
	{ID: "youtube-documentary", Title: "纪录片", Description: "纪录片与人文历史", Query: "纪录片 人文 历史"},
	{ID: "youtube-movies", Title: "电影", Description: "电影剪辑与完整片段", Query: "电影 完整版 片段"},
	{ID: "youtube-learning", Title: "学习", Description: "课程、讲座与知识", Query: "学习 课程 讲座"},
}

func youtubeCategoryByID(id string) (youtubeCategory, bool) {
	for _, category := range defaultYouTubeCategories {
		if category.ID == id {
			return category, true
		}
	}
	return youtubeCategory{}, false
}

func (s *mediaServer) youtubeRealtimeEnabled() bool {
	return s.yt != nil && strings.TrimSpace(s.yt.vodExtractorURL) != ""
}

func publicYouTubeCategories() []PublicCategory {
	items := make([]PublicCategory, 0, len(defaultYouTubeCategories))
	for _, category := range defaultYouTubeCategories {
		items = append(items, PublicCategory{
			ID:          category.ID,
			Title:       category.Title,
			Description: category.Description,
		})
	}
	return items
}

// handleYouTubeCategoryVideos 把 YouTube 分类映射为一次实时 ytsearch。
// 与 /providers/youtube/search 共用鉴权与限流:分类浏览同样是 yt-dlp 上游调用。
func (s *mediaServer) handleYouTubeCategoryVideos(w http.ResponseWriter, r *http.Request, categoryID string) {
	category, ok := youtubeCategoryByID(categoryID)
	if !ok {
		s.writeError(w, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到分类。"})
		return
	}
	if !s.requireAppToken(w, r) {
		return
	}
	if !s.youtubeSearchLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "搜索过于频繁,请稍后再试。"})
		return
	}
	if !s.youtubeRealtimeEnabled() {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未启用视频解析服务,搜索不可用。"})
		return
	}
	page, pageSize, pageErr := paginationFromRequest(r)
	if pageErr != nil {
		s.writeError(w, pageErr)
		return
	}
	public := PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description}
	// ytsearch 是单页实时结果,没有可靠的翻页令牌;第 2 页起返回空页,
	// 让客户端按 total=len(items) 正常终止,而不是重复第一页。
	if page > 1 {
		s.writeJSON(w, http.StatusOK, VideosResponse{
			Category: &public, Page: page, PageSize: pageSize, Total: 0, Items: []VideoSummary{},
		})
		return
	}
	items, err := s.yt.searchVideos(r.Context(), category.Query)
	if err != nil {
		log.Printf("youtube category %s search %q: %v", category.ID, category.Query, err)
		s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_ERROR", Message: "YouTube 搜索暂不可用,请稍后再试。"})
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=60")
	s.writeJSON(w, http.StatusOK, VideosResponse{
		Category: &public,
		Page:     1,
		PageSize: pageSize,
		Total:    len(items),
		Items:    items,
	})
}
