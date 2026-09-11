package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// youtube.go — youtube provider 的两个实时上游通道:
//
//	search  : YouTube Data API v3(search.list 取结果,videos.list 补时长)
//	resolve : 播放解析代理 → vod_extractor(yt-dlp --dump-single-json) → 挑选可播放格式
//
// media_server 其余部分保持纯目录服务;这两个端点是 youtube provider 的专用能力。
// 均要求 X-App-Token(与凭证端点同一令牌)并独立限流:Data API 有每日硬配额,
// yt-dlp 解析慢(秒级)且 vod_extractor 并发有限,都不能暴露给匿名流量。

// YouTube 视频搜索/解析限流(per-IP,每分钟)。搜索受 Data API 每日配额约束
// (search.list 每日限 100 次调用),阈值必须显著小于配额;解析每次拉起一个
// yt-dlp 进程(vod_extractor 并发上限 2、单次超时 30s),也要挡住批量抓取。
const (
	youtubeSearchPerMinuteLimit  = 8
	youtubeResolvePerMinuteLimit = 20
	// pageToken 是 YouTube 返回的不透明翻页令牌,限制长度防滥用。
	maxYouTubePageTokenBytes = 128
	// resolve 响应(yt-dlp JSON)读取上限。完整 JSON 通常数百 KB(含全部格式
	// 与清单),20MB 与 vod_extractor 的 VOD_EXTRACTOR_MAX_OUTPUT_BYTES 对齐。
	maxYouTubeExtractBytes = 20 << 20
	// 解析 youtube 视频的 watch URL 前缀。
	youtubeWatchURL = "https://www.youtube.com/watch?v="
)

// resolve 端到端超时:vod_extractor 自身 30s 超时,留 10s 余量给网络与 JSON 解析。
const youtubeResolveTimeout = 40 * time.Second

// search 端点单请求超时(Data API 通常 <2s)。
const youtubeSearchTimeout = 15 * time.Second

// ytClient 封装 YouTube Data API v3 与 vod_extractor 的 HTTP 调用。
// http.Client 不设总超时,由每个请求的 context 控制(两类调用超时差异大)。
type ytClient struct {
	apiKey          string
	vodExtractorURL string
	http            *http.Client
}

func newYTClient(apiKey, vodExtractorURL string) *ytClient {
	return &ytClient{
		apiKey:          apiKey,
		vodExtractorURL: strings.TrimRight(vodExtractorURL, "/"),
		http:            &http.Client{},
	}
}

// ---- YouTube Data API v3 ----

type ytThumbnails struct {
	Default ytThumbnail `json:"default"`
	Medium  ytThumbnail `json:"medium"`
	High    ytThumbnail `json:"high"`
}

func (t ytThumbnails) bestURL() string {
	switch {
	case t.High.URL != "":
		return t.High.URL
	case t.Medium.URL != "":
		return t.Medium.URL
	default:
		return t.Default.URL
	}
}

type ytThumbnail struct {
	URL string `json:"url"`
}

type ytSearchItem struct {
	ID struct {
		VideoID string `json:"videoId"`
	} `json:"id"`
	Snippet struct {
		Title        string       `json:"title"`
		Description  string       `json:"description"`
		ChannelTitle string       `json:"channelTitle"`
		Thumbnails   ytThumbnails `json:"thumbnails"`
	} `json:"snippet"`
}

type ytSearchResponse struct {
	NextPageToken string      `json:"nextPageToken"`
	Error         *ytAPIError `json:"error"`
	PageInfo      struct {
		TotalResults int64 `json:"totalResults"`
	} `json:"pageInfo"`
	Items []ytSearchItem `json:"items"`
}

type ytVideosResponse struct {
	Error *ytAPIError `json:"error"`
	Items []struct {
		ID             string `json:"id"`
		ContentDetails struct {
			Duration string `json:"duration"`
		} `json:"contentDetails"`
	} `json:"items"`
}

type ytAPIError struct {
	Code   int `json:"code"`
	Errors []struct {
		Reason string `json:"reason"`
	} `json:"errors"`
}

func (e *ytAPIError) reason() string {
	for _, item := range e.Errors {
		if item.Reason != "" {
			return item.Reason
		}
	}
	return ""
}

// searchVideos 调 search.list(type=video)取一页结果,再调 videos.list 批量补时长。
// 返回 VideoSummary 列表与翻页令牌;调用方负责把内部错误转成日志 + 固定文案。
func (c *ytClient) searchVideos(ctx context.Context, query, pageToken string) ([]VideoSummary, string, int64, error) {
	params := url.Values{}
	params.Set("part", "snippet")
	params.Set("type", "video")
	params.Set("videoEmbeddable", "true")
	params.Set("safeSearch", "moderate")
	params.Set("maxResults", "20")
	params.Set("q", query)
	params.Set("key", c.apiKey)
	if pageToken != "" {
		params.Set("pageToken", pageToken)
	}
	proxyPath := "https://www.googleapis.com/youtube/v3/search?" + params.Encode()
	var searchResp ytSearchResponse
	if err := c.getJSON(ctx, youtubeSearchTimeout, proxyPath, &searchResp); err != nil {
		return nil, "", 0, err
	}
	if searchResp.Error != nil {
		return nil, "", 0, fmt.Errorf("youtube search api: %s (code=%d)", searchResp.Error.reason(), searchResp.Error.Code)
	}

	durations := make(map[string]int64, len(searchResp.Items))
	if ids := videoIDs(searchResp.Items); len(ids) > 0 {
		vparams := url.Values{}
		vparams.Set("part", "contentDetails")
		vparams.Set("id", strings.Join(ids, ","))
		vparams.Set("key", c.apiKey)
		var videosResp ytVideosResponse
		if err := c.getJSON(ctx, youtubeSearchTimeout, "https://www.googleapis.com/youtube/v3/videos?"+vparams.Encode(), &videosResp); err != nil {
			// 时长是增强信息,失败不否决整个搜索,只降级为无时长。
			log.Printf("youtube videos.list: %v", err)
		} else if videosResp.Error != nil {
			log.Printf("youtube videos.list api: %s (code=%d)", videosResp.Error.reason(), videosResp.Error.Code)
		} else {
			for _, item := range videosResp.Items {
				if seconds := parseISODuration(item.ContentDetails.Duration); seconds > 0 {
					durations[item.ID] = seconds
				}
			}
		}
	}

	summaries := make([]VideoSummary, 0, len(searchResp.Items))
	for _, item := range searchResp.Items {
		if item.ID.VideoID == "" {
			continue
		}
		title := item.Snippet.Title
		if item.Snippet.ChannelTitle != "" {
			title = item.Snippet.ChannelTitle + ": " + title
		}
		summaries = append(summaries, VideoSummary{
			ID:              item.ID.VideoID,
			Title:           title,
			Description:     item.Snippet.Description,
			ThumbnailURL:    item.Snippet.Thumbnails.bestURL(),
			DurationSeconds: durations[item.ID.VideoID],
		})
	}
	return summaries, searchResp.NextPageToken, searchResp.PageInfo.TotalResults, nil
}

func videoIDs(items []ytSearchItem) []string {
	ids := make([]string, 0, len(items))
	for _, item := range items {
		if item.ID.VideoID != "" {
			ids = append(ids, item.ID.VideoID)
		}
	}
	return ids
}

// getJSON 执行 GET 并把响应解码到 out。非 2xx 时返回包含状态码的错误;
// 响应体限长,防止异常上游拖垮内存。
func (c *ytClient) getJSON(ctx context.Context, timeout time.Duration, rawURL string, out any) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("upstream request: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxYouTubeExtractBytes))
	if err != nil {
		return fmt.Errorf("read upstream body: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("upstream status %d: %s", resp.StatusCode, truncateBody(body))
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("decode upstream json: %w", err)
	}
	return nil
}

func truncateBody(body []byte) string {
	const max = 200
	if len(body) > max {
		return string(body[:max])
	}
	return string(body)
}

// parseISODuration 解析 ISO 8601 时长(YouTube contentDetails.duration,如
// "PT4M13S"、"PT1H2M10S"、"P1DT2H")。YouTube 视频不会超过天级,但按规范
// 完整解析天/时/分/秒;格式非法返回 0。
func parseISODuration(raw string) int64 {
	if raw == "" || !strings.HasPrefix(raw, "P") {
		return 0
	}
	total := int64(0)
	number := int64(0)
	hasNumber := false
	for _, ch := range raw[1:] {
		switch {
		case ch >= '0' && ch <= '9':
			number = number*10 + int64(ch-'0')
			hasNumber = true
		case ch == 'D':
			if !hasNumber {
				return 0
			}
			total += number * 86400
			number, hasNumber = 0, false
		case ch == 'H':
			if !hasNumber {
				return 0
			}
			total += number * 3600
			number, hasNumber = 0, false
		case ch == 'M':
			if !hasNumber {
				return 0
			}
			total += number * 60
			number, hasNumber = 0, false
		case ch == 'S':
			if !hasNumber {
				return 0
			}
			total += number
			number, hasNumber = 0, false
		case ch == 'T':
			// 时间段开始,忽略
		default:
			return 0
		}
	}
	return total
}

// ---- vod_extractor(yt-dlp)解析代理 ----

type ytdlpFormat struct {
	FormatID   string `json:"format_id"`
	URL        string `json:"url"`
	Ext        string `json:"ext"`
	Height     int    `json:"height"`
	FPS        int    `json:"fps"`
	VCodec     string `json:"vcodec"`
	ACodec     string `json:"acodec"`
	Protocol   string `json:"protocol"`
	FormatNote string `json:"format_note"`
}

type ytdlpInfo struct {
	ID        string        `json:"id"`
	Title     string        `json:"title"`
	Duration  float64       `json:"duration"`
	Thumbnail string        `json:"thumbnail"`
	Formats   []ytdlpFormat `json:"formats"`
}

// resolveVideo 调 vod_extractor 解析视频,返回可播放的完整结果(流选项+清晰度列表)。
// 请求体为 JSON {"url": ...}(见 vod_extractor/README.md)。
func (c *ytClient) resolveVideo(ctx context.Context, videoID string) (PlaybackResultResponse, error) {
	payload, err := json.Marshal(map[string]string{"url": youtubeWatchURL + videoID})
	if err != nil {
		return PlaybackResultResponse{}, fmt.Errorf("encode extractor request: %w", err)
	}
	ctx, cancel := context.WithTimeout(ctx, youtubeResolveTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.vodExtractorURL+"/extract", bytes.NewReader(payload))
	if err != nil {
		return PlaybackResultResponse{}, fmt.Errorf("build extractor request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return PlaybackResultResponse{}, fmt.Errorf("extractor request: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxYouTubeExtractBytes))
	if err != nil {
		return PlaybackResultResponse{}, fmt.Errorf("read extractor body: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return PlaybackResultResponse{}, fmt.Errorf("extractor status %d: %s", resp.StatusCode, truncateBody(raw))
	}
	var info ytdlpInfo
	if err := json.Unmarshal(raw, &info); err != nil {
		return PlaybackResultResponse{}, fmt.Errorf("decode extractor json: %w", err)
	}
	result := buildYouTubePlayback(info)
	if len(result.Options) == 0 {
		return PlaybackResultResponse{}, fmt.Errorf("no playable formats (video may require cookies or is restricted)")
	}
	return result, nil
}

// ---- 格式选择:yt-dlp formats → 播放选项 ----

// PlaybackOptionResponse 与 App 端 PlaybackOption(kotlinx.serialization)字段一一对应。
type PlaybackOptionResponse struct {
	ID       string            `json:"id"`
	Kind     string            `json:"kind"`
	Label    string            `json:"label"`
	URL      string            `json:"url"`
	AudioURL string            `json:"audioUrl,omitempty"`
	MimeType string            `json:"mimeType,omitempty"`
	Headers  map[string]string `json:"headers"`
	QN       int               `json:"qn"`
}

// QualityOptionResponse 与 App 端 QualityOption 对应(qn 直接用视频高度,如 1080)。
type QualityOptionResponse struct {
	QN    int    `json:"qn"`
	Label string `json:"label"`
}

// PlaybackResultResponse 与 App 端 PlaybackResult 对应;YouTube 单视频无多P概念,
// pages 省略(App 端默认单页)。
type PlaybackResultResponse struct {
	Options            []PlaybackOptionResponse `json:"options"`
	AvailableQualities []QualityOptionResponse  `json:"availableQualities"`
	DurationSeconds    int64                    `json:"durationSeconds,omitempty"`
}

// isPlayableVideo 判断一个 yt-dlp format 是否包含视频轨且可直接播放
// (排除纯音频与 storyboard/图片格式)。
func isPlayableVideo(f ytdlpFormat) bool {
	if f.URL == "" {
		return false
	}
	// vcodec/acodec 为 "none" 表示该格式不含对应轨道。
	return f.VCodec != "none" && f.VCodec != ""
}

// isProgressive 判断是否音视频合一的直链(单文件即可完整播放,兼容性最好)。
func isProgressive(f ytdlpFormat) bool {
	return f.ACodec != "none" && f.ACodec != "" && isPlayableVideo(f)
}

// isHLS 判断是否 HLS 播放列表(yt-dlp protocol 为 m3u8/m3u8_native)。
func isHLS(f ytdlpFormat) bool {
	return strings.Contains(f.Protocol, "m3u8")
}

// buildYouTubePlayback 从 yt-dlp 的 formats 中挑选可播放流,生成播放选项。
//
// 选择策略(App 播放器为 ExoPlayer/Media3,TV 盒子硬解以 H.264 为主):
//  1. progressive mp4(音视频合一直链):兼容性最好,优先;
//  2. HLS 播放列表(m3u8):覆盖高分辨率档位,每档高度取 fps 最高的一条;
//  3. 同一高度同时存在 progressive 与 HLS 时保留 progressive(直链无分片开销)。
//
// 输出按高度降序排列;qn 直接采用视频高度(1080/720/...),App 端切清晰度
// 直接在 options 内本地换源,不需要重新解析。
func buildYouTubePlayback(info ytdlpInfo) PlaybackResultResponse {
	progressive := make([]ytdlpFormat, 0, 4)
	hls := make([]ytdlpFormat, 0, 8)
	for _, f := range info.Formats {
		if !isPlayableVideo(f) {
			continue
		}
		if f.Height <= 0 {
			continue
		}
		if isProgressive(f) {
			progressive = append(progressive, f)
		} else if isHLS(f) {
			hls = append(hls, f)
		}
	}

	// HLS 每档高度取 fps 最高的代表(YouTube HLS 同高度常有多 fps 变体)。
	bestByHeight := make(map[int]ytdlpFormat, len(hls))
	for _, f := range hls {
		if cur, ok := bestByHeight[f.Height]; !ok || f.FPS > cur.FPS {
			bestByHeight[f.Height] = f
		}
	}
	// progressive 覆盖同高度的 HLS 代表。
	for _, f := range progressive {
		if cur, ok := bestByHeight[f.Height]; !ok || isProgressive(f) && f.FPS >= cur.FPS {
			bestByHeight[f.Height] = f
		}
	}

	heights := make([]int, 0, len(bestByHeight))
	for h := range bestByHeight {
		heights = append(heights, h)
	}
	// 高度降序,同高度 progressive 优先(直接排除了重复,无需二次排序)。
	for i := 0; i < len(heights); i++ {
		for j := i + 1; j < len(heights); j++ {
			if heights[j] > heights[i] {
				heights[i], heights[j] = heights[j], heights[i]
			}
		}
	}

	options := make([]PlaybackOptionResponse, 0, len(heights))
	qualities := make([]QualityOptionResponse, 0, len(heights))
	for _, h := range heights {
		f := bestByHeight[h]
		mime := "video/mp4"
		if isHLS(f) {
			mime = "application/x-mpegurl"
		}
		label := heightLabel(h, f.FPS)
		options = append(options, PlaybackOptionResponse{
			ID:       "yt-" + info.ID + "-" + f.FormatID,
			Kind:     "vod",
			Label:    label,
			URL:      f.URL,
			MimeType: mime,
			Headers:  map[string]string{},
			QN:       h,
		})
		qualities = append(qualities, QualityOptionResponse{QN: h, Label: label})
	}
	var duration int64
	if info.Duration > 0 {
		duration = int64(info.Duration)
	}
	return PlaybackResultResponse{Options: options, AvailableQualities: qualities, DurationSeconds: duration}
}

// heightLabel 生成清晰度展示标签:>=50fps 标注帧率,与 B 站 "1080P60" 风格一致。
func heightLabel(height, fps int) string {
	label := strconv.Itoa(height) + "P"
	if fps >= 50 {
		label += strconv.Itoa(fps)
	}
	return label
}

// isValidYouTubeVideoID 校验 11 字符视频 ID 格式([A-Za-z0-9_-]{11}),
// 防止把任意 URL 注入 vod_extractor。
func isValidYouTubeVideoID(id string) bool {
	if len(id) != 11 {
		return false
	}
	for _, ch := range id {
		switch {
		case ch >= '0' && ch <= '9':
		case ch >= 'a' && ch <= 'z':
		case ch >= 'A' && ch <= 'Z':
		case ch == '-' || ch == '_':
		default:
			return false
		}
	}
	return true
}

// ---- HTTP handlers ----

// requireAppToken 校验 X-App-Token(与凭证端点同一令牌)。未配置 token 时一律拒绝。
func (s *mediaServer) requireAppToken(w http.ResponseWriter, r *http.Request) bool {
	expected := s.config.AppAPIToken
	if expected == "" || !constantTimeEqual(r.Header.Get("X-App-Token"), expected) {
		s.writeError(w, &apiError{Status: http.StatusUnauthorized, Code: "UNAUTHORIZED", Message: "接口未授权。"})
		return false
	}
	return true
}

// handleYouTubeSearch 实现 GET /api/providers/youtube/search?q=&pageToken=
// 实时搜索 YouTube(Data API v3),结果不写 catalog,仅供 App 即时展示与播放。
func (s *mediaServer) handleYouTubeSearch(w http.ResponseWriter, r *http.Request) {
	if !s.youtubeSearchLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "搜索过于频繁,请稍后再试。"})
		return
	}
	if !s.requireAppToken(w, r) {
		return
	}
	if s.yt.apiKey == "" {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未配置 YouTube API Key,搜索不可用。"})
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		s.writeJSON(w, http.StatusOK, SearchResponse{Query: "", Items: []VideoSummary{}})
		return
	}
	if len(query) > maxSearchQueryBytes {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "QUERY_TOO_LONG", Message: "搜索关键词过长。"})
		return
	}
	pageToken := r.URL.Query().Get("pageToken")
	if len(pageToken) > maxYouTubePageTokenBytes {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "pageToken 参数不合法。"})
		return
	}
	items, nextPageToken, total, err := s.yt.searchVideos(r.Context(), query, pageToken)
	if err != nil {
		log.Printf("youtube search %q: %v", query, err)
		s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_ERROR", Message: "YouTube 搜索暂不可用,请稍后再试。"})
		return
	}
	s.writeJSON(w, http.StatusOK, SearchResponse{
		Query:         query,
		Total:         total,
		Items:         items,
		NextPageToken: nextPageToken,
	})
}

// handleYouTubeResolve 实现 GET /api/providers/youtube/resolve?id=
// 代理 vod_extractor 解析播放地址,返回 App 端 PlaybackResult 结构。
func (s *mediaServer) handleYouTubeResolve(w http.ResponseWriter, r *http.Request) {
	if !s.youtubeResolveLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "解析请求过于频繁,请稍后再试。"})
		return
	}
	if !s.requireAppToken(w, r) {
		return
	}
	if s.yt.vodExtractorURL == "" {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未启用视频解析服务。"})
		return
	}
	videoID := strings.TrimSpace(r.URL.Query().Get("id"))
	if !isValidYouTubeVideoID(videoID) {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "视频 ID 不合法。"})
		return
	}
	result, err := s.yt.resolveVideo(r.Context(), videoID)
	if err != nil {
		log.Printf("youtube resolve %s: %v", videoID, err)
		s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "RESOLVE_FAILED", Message: "播放地址解析失败,该视频可能受限或稍后再试。"})
		return
	}
	s.writeJSON(w, http.StatusOK, result)
}
