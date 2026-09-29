package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// youtube.go — youtube provider 的两个实时上游通道,全部经由内网的
// vod_extractor(yt-dlp)完成:
//
//	search  : POST /search → yt-dlp `ytsearch{N}:query`(--flat-playlist 只抓
//	          搜索结果页,不逐个解析视频)。与观众浏览器走同一条 YouTube 公开
//	          前端,无 Data API 每日配额;单次耗时秒级,且同样依赖 cookies。
//	resolve : POST /extract → yt-dlp `--dump-single-json` 解析播放直链,挑选
//	          可播放格式(prepgressive mp4 优先、HLS 补高清档)。
//
// media_server 其余部分保持目录服务职责。两个端点均要求 X-App-Token(与凭证
// 端点同一令牌)并独立限流:每次调用都要拉起 yt-dlp 进程(vod_extractor 并发
// 上限 2),不能暴露给匿名流量。

// 搜索/解析限流(per-IP,每分钟)。搜索比解析更重(抓搜索结果页),阈值更保守。
const (
	youtubeSearchPerMinuteLimit  = 8
	youtubeResolvePerMinuteLimit = 20
	// resolve 响应(yt-dlp JSON)读取上限。完整 JSON 通常数百 KB(含全部格式
	// 与清单),20MB 与 vod_extractor 的 VOD_EXTRACTOR_MAX_OUTPUT_BYTES 对齐。
	maxYouTubeExtractBytes = 20 << 20
	// 解析 youtube 视频的 watch URL 前缀。
	youtubeWatchURL = "https://www.youtube.com/watch?v="
	// 搜索每页条数(TV 搜索每键即时出结果,单页足够)。
	youtubeSearchLimit = 20
)

// resolve 端到端超时:vod_extractor 自身 30s 超时,留 10s 余量给网络与 JSON 解析。
const youtubeResolveTimeout = 40 * time.Second

// search 端到端超时:yt-dlp flat 模式抓搜索结果页通常数秒,30s 覆盖慢网。
const youtubeSearchTimeout = 30 * time.Second

// ytClient 封装 vod_extractor 的 HTTP 调用(搜索与播放解析同源)。
// http.Client 不设总超时,由每个请求的 context 控制。
type ytClient struct {
	vodExtractorURL string
	http            *http.Client
}

func newYTClient(vodExtractorURL string) *ytClient {
	return &ytClient{
		vodExtractorURL: strings.TrimRight(vodExtractorURL, "/"),
		http:            &http.Client{},
	}
}

// ---- vod_extractor(yt-dlp)搜索代理 ----

// ytdlpEntry 是 --flat-playlist 搜索结果里的一条视频。duration 在直播/待播
// 条目上可能为 null,用指针接住。
type ytdlpEntry struct {
	ID         string   `json:"id"`
	Title      string   `json:"title"`
	Channel    string   `json:"channel"`
	Duration   *float64 `json:"duration"`
	Thumbnail  string   `json:"thumbnail"`
	Thumbnails []struct {
		URL    string `json:"url"`
		Height int    `json:"height"`
		Width  int    `json:"width"`
	} `json:"thumbnails"`
}

type ytdlpSearchResult struct {
	Type    string       `json:"_type"`
	Entries []ytdlpEntry `json:"entries"`
}

// bestThumbnailURL 从 flat 条目的缩略图字段里挑最高分辨率:thumbnails 列表
// 按 yt-dlp 惯例从低到高排列,取最后一个非空项;退化到单值 thumbnail 字段。
func bestThumbnailURL(e ytdlpEntry) string {
	for i := len(e.Thumbnails) - 1; i >= 0; i-- {
		if e.Thumbnails[i].URL != "" {
			return e.Thumbnails[i].URL
		}
	}
	return e.Thumbnail
}

// searchVideos 调 vod_extractor 的 /search(yt-dlp ytsearch)做实时搜索。
// 结果不写 catalog,仅供 App 即时展示与播放;调用方负责把内部错误转成日志 +
// 固定文案。
func (c *ytClient) searchVideos(ctx context.Context, query string) ([]VideoSummary, error) {
	payload, err := json.Marshal(map[string]any{"query": query, "limit": youtubeSearchLimit})
	if err != nil {
		return nil, fmt.Errorf("encode search request: %w", err)
	}
	ctx, cancel := context.WithTimeout(ctx, youtubeSearchTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.vodExtractorURL+"/search", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("build search request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("search request: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxYouTubeExtractBytes))
	if err != nil {
		return nil, fmt.Errorf("read search body: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("search status %d reason=%s: %s", resp.StatusCode, upstreamFailureReason(raw), truncateBody(raw))
	}
	var result ytdlpSearchResult
	if err := json.Unmarshal(raw, &result); err != nil {
		return nil, fmt.Errorf("decode search json: %w", err)
	}
	summaries := make([]VideoSummary, 0, len(result.Entries))
	for _, e := range result.Entries {
		if e.ID == "" {
			continue
		}
		title := e.Title
		if e.Channel != "" {
			title = e.Channel + ": " + title
		}
		var duration int64
		if e.Duration != nil && *e.Duration > 0 {
			duration = int64(*e.Duration)
		}
		summaries = append(summaries, VideoSummary{
			ID:              e.ID,
			Title:           title,
			ThumbnailURL:    bestThumbnailURL(e),
			DurationSeconds: duration,
		})
	}
	return summaries, nil
}

func truncateBody(body []byte) string {
	const max = 200
	if len(body) > max {
		body = body[:max]
	}
	// 上游响应可能是截断/二进制内容;ToValidUTF8 把非法字节序列(如截断点落在
	// 多字节字符中间)替换为 U+FFFD,避免日志出现半截字符。
	return strings.ToValidUTF8(string(body), "�")
}

// upstreamFailureReason 从 vod_extractor 的错误响应体提取失败分类
// (bot_check/rate_limited/unavailable/geo_blocked/...):分类让 cookies 失效
// (bot_check 持续出现)在服务端日志里一眼可辨,排障不再依赖进 extractor 容器翻日志。
func upstreamFailureReason(raw []byte) string {
	var body struct {
		Reason string `json:"reason"`
	}
	if err := json.Unmarshal(raw, &body); err != nil {
		return ""
	}
	return body.Reason
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
		return PlaybackResultResponse{}, fmt.Errorf("extractor status %d reason=%s: %s", resp.StatusCode, upstreamFailureReason(raw), truncateBody(raw))
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
	// progressive 覆盖同高度的 HLS 代表(平局或更高 fps 时才顶掉)。
	for _, f := range progressive {
		if cur, ok := bestByHeight[f.Height]; !ok || f.FPS >= cur.FPS {
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

// handleYouTubeSearch 实现 GET /api/providers/youtube/search?q=
// 实时搜索 YouTube(经 vod_extractor 的 yt-dlp ytsearch,与观众浏览器同一
// 公开前端,无 Data API 配额),结果不写 catalog,仅供 App 即时展示与播放。
func (s *mediaServer) handleYouTubeSearch(w http.ResponseWriter, r *http.Request) {
	// 鉴权先于限流：无 token 的请求不消耗 per-IP 配额，否则攻击者可用
	// 401 高频请求烧光同一出口 IP 上合法 App 的配额（定向限流 DoS）。
	if !s.requireAppToken(w, r) {
		return
	}
	if !s.youtubeSearchLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "搜索过于频繁,请稍后再试。"})
		return
	}
	if s.yt.vodExtractorURL == "" {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未启用视频解析服务,搜索不可用。"})
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
	items, err := s.yt.searchVideos(r.Context(), query)
	if err != nil {
		log.Printf("youtube search %q: %v", query, err)
		s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_ERROR", Message: "YouTube 搜索暂不可用,请稍后再试。"})
		return
	}
	s.writeJSON(w, http.StatusOK, SearchResponse{
		Query: query,
		Total: int64(len(items)),
		Items: items,
	})
}

// handleYouTubeResolve 实现 GET /api/providers/youtube/resolve?id=
// 代理 vod_extractor 解析播放地址,返回 App 端 PlaybackResult 结构。
func (s *mediaServer) handleYouTubeResolve(w http.ResponseWriter, r *http.Request) {
	// 鉴权先于限流（同 handleYouTubeSearch）。
	if !s.requireAppToken(w, r) {
		return
	}
	if !s.youtubeResolveLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "解析请求过于频繁,请稍后再试。"})
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
