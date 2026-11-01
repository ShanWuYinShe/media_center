package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// youtube.go — youtube provider 的两个实时上游通道,全部经由内网的
// vod_extractor(yt-dlp)完成:
//
//	search  : POST /search → yt-dlp `ytsearch{N}:query`(--flat-playlist 只抓
//	          搜索结果页,不逐个解析视频)。与观众浏览器走同一条 YouTube 公开
//	          前端,无 Data API 每日配额;单次耗时秒级。flat 列表页不触发播放
//	          风控,无 cookies 仍可用(2026-10-12 线上实测)。
//	resolve : POST /extract → yt-dlp `--dump-single-json` 解析播放直链,挑选
//	          可播放格式(progressive mp4 优先、HLS 补高清档)。数据中心 IP
//	          解析必须带 cookies,否则触发 "Sign in to confirm you're not a bot"。
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

// resolve 端到端超时:vod_extractor 自身 60s 超时(Deno 求解签名 + PO Token
// 生成冷启动实测 25–30s),留 10s 余量给网络与 JSON 解析。
const youtubeResolveTimeout = 70 * time.Second

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

// upstreamError 是 vod_extractor 返回非 200 的结构化失败,供负缓存判定
// (只缓存真实解析失败,不缓存瞬时繁忙)与日志。
type upstreamError struct {
	status int
	reason string
	body   string
}

func (e *upstreamError) Error() string {
	if e.reason != "" {
		return fmt.Sprintf("extractor status %d reason=%s: %s", e.status, e.reason, e.body)
	}
	return fmt.Sprintf("extractor status %d: %s", e.status, e.body)
}

// ---- vod_extractor(yt-dlp)解析代理 ----

// ---- resolve 结果缓存:削 yt-dlp 进程峰值、防同 ID 重试风暴 ----
//
// 成功缓存 TTL 15min:YouTube 直链时效约 6h,15min 内复用安全;App 反复进出
// 播放页不再重复 spawn yt-dlp(解析是全系统最重的上游动作,vod_extractor 并发
// 上限只有 2)。负缓存 TTL 5min:失败结果(仅限 extractor 真实解析失败 502/
// 超时 504)短期复用,用户连点重试不再反复撞 YouTube 风控——每次真实解析都在
// 消耗 cookies 寿命。换 cookies 后最多等 5min 自动恢复;容器重启即清空,可接受。
const (
	resolvePositiveCacheTTL = 15 * time.Minute
	resolveNegativeCacheTTL = 5 * time.Minute
	maxResolveCacheEntries  = 128
)

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
		return PlaybackResultResponse{}, &upstreamError{
			status: resp.StatusCode,
			reason: upstreamFailureReason(raw),
			body:   truncateBody(raw),
		}
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

// 播放候选的接入方式,数值越小越优先:
//  1. 音视频合一直链:单文件、无二次请求,兼容性最好;
//  2. 音视频合一 HLS:原生分片,音轨内嵌;
//  3. 视频直链 + 独立音轨:App 用 MergingMediaSource 合流(YouTube 1080P+ 的主力形态);
//  4. 视频 HLS + 独立音轨:同上,但走分片。
const (
	streamMuxedProgressive = iota
	streamMuxedHLS
	streamSplitProgressive
	streamSplitHLS
)

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
	if s.yt == nil || s.yt.vodExtractorURL == "" {
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
	if s.yt == nil || s.yt.vodExtractorURL == "" {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未启用视频解析服务。"})
		return
	}
	videoID := strings.TrimSpace(r.URL.Query().Get("id"))
	if !isValidYouTubeVideoID(videoID) {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "视频 ID 不合法。"})
		return
	}
	// 缓存优先:成功项直接回放,负缓存项快速失败(不消耗 yt-dlp 进程配额,
	// 不再撞风控)。未命中才走真实解析。判定必须用 failure 标志而不是
	// reason 是否为空:上游 502 未带结构化 reason 时同样是失败,回放零值
	// 结果会变成一个"成功但没有播放地址"的 200。
	if result, reason, failure, hit := globalResolveCache.lookup(videoID); hit {
		if failure {
			log.Printf("youtube resolve %s: cached failure (reason=%s)", videoID, reason)
			s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "RESOLVE_FAILED", Message: "播放地址解析失败,该视频可能受限或稍后再试。"})
			return
		}
		s.writeJSON(w, http.StatusOK, result)
		return
	}
	// 同 ID 并发请求共享一次 yt-dlp 解析:重试风暴/多端同时打开时不再
	// 各自拉起进程(vod_extractor 并发上限只有 2,还会消耗 cookies 寿命)。
	result, err := globalResolveGroup.do(r.Context(), videoID, func() (PlaybackResultResponse, error) {
		return s.yt.resolveVideo(r.Context(), videoID)
	})
	if err != nil {
		log.Printf("youtube resolve %s: %v", videoID, err)
		// 只负缓存真实解析失败(502 带分类/504 超时);extractor 繁忙(503)
		// 是瞬时的,缓存会把可用窗口错锁成失败。
		var ue *upstreamError
		if errors.As(err, &ue) && (ue.status == http.StatusBadGateway || ue.status == http.StatusGatewayTimeout) {
			globalResolveCache.store(videoID, resolveCacheEntry{
				failure:   true,
				reason:    ue.reason,
				expiresAt: time.Now().Add(resolveNegativeCacheTTL),
			})
		}
		s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "RESOLVE_FAILED", Message: "播放地址解析失败,该视频可能受限或稍后再试。"})
		return
	}
	globalResolveCache.store(videoID, resolveCacheEntry{
		result:    result,
		expiresAt: time.Now().Add(resolvePositiveCacheTTL),
	})
	s.writeJSON(w, http.StatusOK, result)
}
