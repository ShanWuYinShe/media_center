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
	youtubeSearchPerMinuteLimit  = 12
	youtubeResolvePerMinuteLimit = 20
	// resolve 响应(yt-dlp JSON)读取上限。完整 JSON 通常数百 KB、格式很多的长
	// 视频也就 1~2MB;8MB 留 4 倍余量,并把 64M 容器里单请求最坏分配从 20MB
	// 压到 8MB。与 vod_extractor 的 VOD_EXTRACTOR_MAX_OUTPUT_BYTES 默认值对齐
	// (extractor 侧同步收紧到 8MB,超限时它自己先失败,不会产出被这里截断的响应)。
	maxYouTubeExtractBytes = 8 << 20
	// 解析 youtube 视频的 watch URL 前缀。
	youtubeWatchURL = "https://www.youtube.com/watch?v="
	// 搜索每页条数(TV 搜索每键即时出结果,单页足够)。
	youtubeSearchLimit = 20
)

// resolve 端到端超时:vod_extractor 自身 60s 超时(Deno 求解签名 + PO Token
// 生成冷启动实测 25–30s),留 10s 余量给网络与 JSON 解析。
const youtubeResolveTimeout = 70 * time.Second

// search 端到端超时:yt-dlp flat 模式抓搜索结果页通常数秒;最坏路径是
// "排队 3s + busy 重试 3s + 排队 3s + 上游 20s"=29s(vod_extractor 的搜索
// 超时单独收紧到 20s),35s 留足网络余量。旧值 30s 会在这条路径上先于
// 上游放弃,而 extractor 侧进程仍在跑、继续占槽。
const youtubeSearchTimeout = 35 * time.Second

// extractor 并发满时的排队与重试:
// vod_extractor 满槽不再立刻 503,而是排队等待(默认 3s);等不到才回
// busy(503, reason=busy)。media_server 对 busy 再做一次短退避重试,
// 短突发(前一个解析刚好结束)时第三台电视能等到槽位起播,而不是直接报
// "解析失败,该视频可能受限"(误导用户以为视频问题)。
const (
	extractorBusyRetries = 1
	extractorBusyReason  = "busy"
)

// 退避时长做成变量:测试里改成毫秒级,避免单测真的等 3s。
var extractorBusyBackoff = 3 * time.Second

// ytClient 封装 vod_extractor 的 HTTP 调用(搜索与播放解析同源)。
// http.Client 不设总超时,由每个请求的 context 控制。
type ytClient struct {
	vodExtractorURL string
	// publicBase 是本服务的公网基址(MEDIA_SERVER_PUBLIC_URL):非空时,
	// 搜索/分类结果里的封面 URL 改写为服务端缩略图代理(见 youtube_thumb.go)——
	// 国内直连 i.ytimg.com 不可达。空串保持原始地址(本地开发/未配置)。
	publicBase string
	http       *http.Client
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

// thumbURL 生成封面地址:配置了公网基址时返回服务端缩略图代理地址
// (国内直连 i.ytimg.com 不可达,见 youtube_thumb.go);否则回退原始地址。
func (c *ytClient) thumbURL(videoID, quality, fallback string) string {
	if url := youtubeThumbURL(c.publicBase, videoID, quality); url != "" {
		return url
	}
	return fallback
}

// searchVideos 调 vod_extractor 的 /search(yt-dlp ytsearch)做实时搜索。
// 结果不写 catalog,仅供 App 即时展示与播放;调用方负责把内部错误转成日志 +
// 固定文案。
func (c *ytClient) searchVideos(ctx context.Context, query string) ([]VideoSummary, error) {
	raw, err := c.postExtractor(ctx, "/search", map[string]any{
		"query": query,
		"limit": youtubeSearchLimit,
	}, youtubeSearchTimeout)
	if err != nil {
		return nil, err
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
			ID:                e.ID,
			Title:             title,
			ThumbnailURL:      c.thumbURL(e.ID, youtubeThumbQualityHQ, youtubeCardThumbnail(e)),
			ThumbnailURLLarge: c.thumbURL(e.ID, youtubeThumbQualityMaxres, bestThumbnailURL(e)),
			DurationSeconds:   duration,
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

// postExtractor 向 vod_extractor 发 POST 并读取 JSON 响应;遇到"并发槽已满"
// (503 reason=busy)时做一次短退避重试——这是多端同时播放时第三台电视能等到
// 前一个解析结束起播、而不是直接拿到 502 的关键。返回 200 响应体;非 200 统一
// 转成 *upstreamError(保留 status/reason,供负缓存与 handler 映射)。
// timeout 是该端点端到端预算;调用方取消/到期会提前中断。
func (c *ytClient) postExtractor(ctx context.Context, path string, payload any, timeout time.Duration) ([]byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("encode extractor request: %w", err)
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var lastErr error
	for attempt := 0; attempt <= extractorBusyRetries; attempt++ {
		if attempt > 0 {
			if err := sleepContext(ctx, extractorBusyBackoff); err != nil {
				return nil, err
			}
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.vodExtractorURL+path, bytes.NewReader(body))
		if err != nil {
			return nil, fmt.Errorf("build extractor request: %w", err)
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := c.http.Do(req)
		if err != nil {
			return nil, fmt.Errorf("extractor request: %w", err)
		}
		// 多读 1 字节用于识别超限:LimitReader 到上限即 EOF,读满上限时无法
		// 区分"刚好读满"和"还有更多";超过上限必须报错,不能把截断 JSON 当成功。
		raw, readErr := io.ReadAll(io.LimitReader(resp.Body, maxYouTubeExtractBytes+1))
		resp.Body.Close()
		if readErr != nil {
			return nil, fmt.Errorf("read extractor body: %w", readErr)
		}
		if len(raw) > maxYouTubeExtractBytes {
			return nil, fmt.Errorf("extractor response exceeds %d bytes", maxYouTubeExtractBytes)
		}
		if resp.StatusCode == http.StatusOK {
			return raw, nil
		}
		lastErr = &upstreamError{
			status: resp.StatusCode,
			reason: upstreamFailureReason(raw),
			body:   truncateBody(raw),
		}
		if !isExtractorBusy(lastErr) {
			return nil, lastErr
		}
		log.Printf(
			"extractor %s busy (attempt %d/%d), retry in %s",
			path, attempt+1, extractorBusyRetries+1, extractorBusyBackoff,
		)
	}
	return nil, lastErr
}

// isExtractorBusy 判断错误是否为 extractor 并发槽已满(可重试的瞬时错误)。
// reason=busy 是新版 extractor 返回的结构化字段;body 关键字兜底兼容旧版本。
func isExtractorBusy(err error) bool {
	var ue *upstreamError
	if !errors.As(err, &ue) || ue.status != http.StatusServiceUnavailable {
		return false
	}
	return ue.reason == extractorBusyReason ||
		strings.Contains(strings.ToLower(ue.body), extractorBusyReason)
}

// sleepContext 是可被 ctx 取消的 sleep。
func sleepContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// writeYouTubeBusy 把 extractor 繁忙映射为 503 + Retry-After:这是瞬时容量
// 问题,不是"视频受限";App 对 5xx 显示"服务暂时繁忙,请稍后重试",语义正确。
func (s *mediaServer) writeYouTubeBusy(w http.ResponseWriter) {
	w.Header().Set("Retry-After", "10")
	s.writeError(w, &apiError{
		Status:  http.StatusServiceUnavailable,
		Code:    "SERVICE_BUSY",
		Message: "YouTube 服务繁忙，请稍后重试。",
	})
}

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
	raw, err := c.postExtractor(ctx, "/extract", map[string]string{
		"url": youtubeWatchURL + videoID,
	}, youtubeResolveTimeout)
	if err != nil {
		return PlaybackResultResponse{}, err
	}
	var info ytdlpInfo
	if err := json.Unmarshal(raw, &info); err != nil {
		return PlaybackResultResponse{}, fmt.Errorf("decode extractor json: %w", err)
	}
	result := buildYouTubePlayback(info)
	if len(result.Options) == 0 {
		// 上游 200 但没有任何可播格式:按内容类失败处理(reason=no_formats,
		// 502 → 可负缓存),不要被归入基础设施故障触发熔断/长重试。
		return PlaybackResultResponse{}, &upstreamError{
			status: http.StatusBadGateway,
			reason: "no_formats",
			body:   "resolved result has no playable options",
		}
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
	// 缓存命中是纯内存操作,不应消耗 per-IP 限流配额:旧顺序(先限流后查缓存)
	// 让热词搜索在配额用尽后仍然 429——用户只是反复点同一个热词,却被
	// "搜索过于频繁"拦住。只有真正要打 yt-dlp 的未命中请求才配给限额。
	if items, ok := s.peekYouTubeSearch(query); ok {
		s.cacheStats.searchHits.Add(1)
		s.writeJSON(w, http.StatusOK, SearchResponse{
			Query: query,
			Total: int64(len(items)),
			Items: items,
		})
		return
	}
	// 熔断打开时快速失败(不消耗 per-IP 配额):cookies 失效/风控/extractor
	// 挂掉时,所有电视的每次搜索都去拉起 yt-dlp 只会把 2 个并发槽打满。
	if ok, retryAfter := s.youtubeCircuit().allow(time.Now()); !ok {
		s.writeYouTubeCircuitOpen(w, retryAfter, "UPSTREAM_UNAVAILABLE", "YouTube 服务暂不可用，请稍后重试。")
		return
	}
	if !s.youtubeSearchLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "搜索过于频繁,请稍后再试。"})
		return
	}
	items, err := s.cachedYouTubeSearch(r.Context(), query)
	if err != nil {
		log.Printf("youtube search %q: %v", query, err)
		if isExtractorBusy(err) {
			s.writeYouTubeBusy(w)
			return
		}
		// 请求方自己断开(切页/退出)不是上游故障:不写响应、不记熔断,
		// 否则用户中途离开就会把全站熔断打开。搜索预算耗尽(DeadlineExceeded)
		// 才计入上游超时。
		if errors.Is(err, context.Canceled) && r.Context().Err() != nil {
			return
		}
		// 搜索失败基本都是基础设施类(extractor 连不上/超时/风控/配置错误),
		// 只有旧版 extractor 的裸 502 才落到旧文案(内容类兼容路径)。
		if classifyYouTubeFailure(err) == youtubeFailureInfra {
			s.youtubeCircuit().recordInfraFailure(youtubeFailureLabel(err), time.Now())
			s.writeYouTubeUnavailable(w, "UPSTREAM_UNAVAILABLE", "YouTube 服务暂不可用，请稍后重试。")
			return
		}
		s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_ERROR", Message: "YouTube 搜索暂不可用,请稍后再试。"})
		return
	}
	s.youtubeCircuit().recordSuccess()
	s.writeJSON(w, http.StatusOK, SearchResponse{
		Query: query,
		Total: int64(len(items)),
		Items: items,
	})
}

// handleYouTubeResolve 实现 GET /api/providers/youtube/resolve?id=
// 代理 vod_extractor 解析播放地址,返回 App 端 PlaybackResult 结构。
func (s *mediaServer) handleYouTubeResolve(w http.ResponseWriter, r *http.Request) {
	// 直链带时效签名,禁止缓存留存在客户端/中间代理(过期后回放只会得到 403)。
	w.Header().Set("Cache-Control", "no-store")
	// 鉴权先于限流（同 handleYouTubeSearch）。
	if !s.requireAppToken(w, r) {
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
		s.cacheStats.resolveMemoryHits.Add(1)
		if failure {
			log.Printf("youtube resolve %s: cached failure (reason=%s)", videoID, reason)
			// 负缓存旧条目可能来自修复前的实现(把 cookies/风控等基础设施失败
			// 也写了进去):命中时按故障语义返回,避免继续误导用户以为视频受限。
			if classifyYouTubeFailureReason(reason) == youtubeFailureInfra {
				s.writeYouTubeUnavailable(w, "RESOLVE_UNAVAILABLE", "解析服务暂不可用，请稍后重试。")
				return
			}
			s.writeError(w, &apiError{Status: http.StatusBadGateway, Code: "RESOLVE_FAILED", Message: "播放地址解析失败,该视频可能受限或稍后再试。"})
			return
		}
		s.writeJSON(w, http.StatusOK, result)
		return
	}
	// 进程内未命中再查磁盘持久缓存(跨重启/发布存活);命中后回填内存缓存,
	// 后续请求连 SQLite 都不用查。
	if s.resolveDisk != nil {
		if result, ok := s.resolveDisk.lookup(videoID); ok {
			s.cacheStats.resolveDiskHits.Add(1)
			globalResolveCache.store(videoID, resolveCacheEntry{
				result:    result,
				expiresAt: time.Now().Add(resolvePositiveCacheTTL),
			})
			s.writeJSON(w, http.StatusOK, result)
			return
		}
	}
	// 缓存(内存+磁盘)未命中才消耗 per-IP 配额:重复进出播放页/家庭多端共享
	// 出口 IP 时,纯缓存命中不应收到 429(与搜索/分类口径一致)。熔断检查在
	// 限流之前:上游已确认故障时直接快失败,不消耗配额。
	if ok, retryAfter := s.youtubeCircuit().allow(time.Now()); !ok {
		s.writeYouTubeCircuitOpen(w, retryAfter, "RESOLVE_UNAVAILABLE", "解析服务暂不可用，请稍后重试。")
		return
	}
	if !s.youtubeResolveLimiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "解析请求过于频繁,请稍后再试。"})
		return
	}
	// 同 ID 并发请求共享一次 yt-dlp 解析:重试风暴/多端同时打开时不再
	// 各自拉起进程(vod_extractor 并发上限只有 2,还会消耗 cookies 寿命)。
	// 上游调用用脱离调用方取消的 ctx:leader 用户退出播放页不应让其它等待者
	// 一起失败(否则表现为随机 502);仍受 70s+10s 超时保护。
	flightCtx, cancelFlight := context.WithTimeout(
		context.WithoutCancel(r.Context()), youtubeResolveTimeout+10*time.Second,
	)
	defer cancelFlight()
	result, err := globalResolveGroup.do(r.Context(), videoID, func() (PlaybackResultResponse, error) {
		// 只有 leader 真正打上游;等待者复用结果,不计入 upstream。
		s.cacheStats.resolveUpstreams.Add(1)
		startedAt := time.Now()
		result, err := s.yt.resolveVideo(flightCtx, videoID)
		s.cacheStats.recordResolveUpstream(startedAt)
		return result, err
	})
	if err == nil && len(result.Options) == 0 {
		// 空结果按真实解析失败处理:否则会返回"成功但没有播放地址"的 200
		// 并被正缓存,用户只能反复重试。
		err = &upstreamError{status: http.StatusBadGateway, reason: "no_formats", body: "resolved result has no playable options"}
	}
	if err != nil {
		log.Printf("youtube resolve %s: %v", videoID, err)
		// 请求方自己断开(退出播放页/切视频)不是上游故障:不写响应、不记熔断。
		if errors.Is(err, context.Canceled) && r.Context().Err() != nil {
			return
		}
		// 容量繁忙 ≠ 视频受限:返回 503 + Retry-After,让 App 提示"服务繁忙"
		// 而不是误导用户以为该视频不可播。
		if isExtractorBusy(err) {
			s.writeYouTubeBusy(w)
			return
		}
		// 基础设施类失败(cookies 过期/风控/extractor 挂掉/超时/配置错误):
		// 503 + Retry-After,不按视频负缓存(否则全站故障会被错锁 5 分钟),
		// 并计入进程级熔断,避免用户换视频反复触发长解析占满 extractor 槽。
		if classifyYouTubeFailure(err) == youtubeFailureInfra {
			s.youtubeCircuit().recordInfraFailure(youtubeFailureLabel(err), time.Now())
			s.writeYouTubeUnavailable(w, "RESOLVE_UNAVAILABLE", "解析服务暂不可用，请稍后重试。")
			return
		}
		// 只负缓存内容类失败(视频受限/无格式等);extractor 繁忙是瞬时的,
		// 缓存会把可用窗口错锁成失败。
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
	s.youtubeCircuit().recordSuccess()
	globalResolveCache.store(videoID, resolveCacheEntry{
		result:    result,
		expiresAt: time.Now().Add(resolvePositiveCacheTTL),
	})
	if s.resolveDisk != nil {
		s.resolveDisk.store(videoID, result)
	}
	s.writeJSON(w, http.StatusOK, result)
}
