package main

import (
	"crypto/rand"
	"encoding/hex"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// youtube_stream.go — YouTube 播放流中转。
//
// **为什么需要**:国内不仅 i.ytimg.com(封面)不可达，googlevideo.com(播放流)
// 同样不可达(DNS 被污染 + TLS 失败)。resolve 成功返回的 googlevideo 直链
// 在电视上无法直接拉流,播放仍会失败。
//
// **方案**:resolve 时把每个直链注册进内存映射(随机 token → googlevideo URL,
// TTL 6h 与直链时效一致),PlaybackOption.URL/AudioURL 改写为本服务的
// /api/providers/youtube/stream/{token};播放器拉流时由服务端代取并透传
// 字节。关键点:googlevideo 直链与签发时 IP 绑定——resolve 和取流都发生在
// 服务端(同一出口 IP),所以代取始终有效;客户端从头到尾不接触 googlevideo。
//
// **缓存一致性**:改写只发生在响应 App 的最后一刻(resolve 内存/磁盘缓存
// 里存的仍是原始直链),磁盘缓存跨重启后仍可用;重启后旧 token 失效,
// App 重新 resolve 即拿到新 token(客户端本来也要在直链过期后重新解析)。
//
// **超时与流式**:该路径绕过 apiTimeoutHandler(75s 会砍断长视频),
// 与 /api/app/download 同一豁免机制;上游按请求 ctx 流式转发,客户端
// 断开即取消,不做整体超时。http.Client 不设 Timeout(它会覆盖整个响应体),
// 连接/首包超时交给 Transport。
//
// **鉴权**:token 为 16 字节随机数(32 hex,不可猜测),且只能映射到某个
// 已通过 X-App-Token resolve 出来的直链——拿到 token 等价于已经拿到过
// resolve 结果,故端点本身不再验 token(播放器数据源不附带令牌)。
const (
	// 直链有效期 YouTube 约 6 小时;token 与之同寿命。
	youtubeStreamTokenTTL = 6 * time.Hour
	// token 映射容量上限:LRU 淘汰,防止无界增长。每条 ~2KB(URL),
	// 512 条 ~1MB,远在容器内存预算内。
	youtubeStreamMaxEntries = 512
	// 连接与首响应头的超时:字节流转发不受它约束(否则长视频会被砍断)。
	youtubeStreamDialTimeout = 30 * time.Second
)

// streamEntry 是一条 token → googlevideo 直链映射。
type streamEntry struct {
	upstream  string
	expiresAt time.Time
	addedAt   time.Time
}

// youtubeStreamProxy 管理直链映射并执行流式转发。
type youtubeStreamProxy struct {
	client *http.Client

	mu      sync.Mutex
	entries map[string]streamEntry
	order   []string // 按最近使用排序,尾部最旧
}

func newYouTubeStreamProxy() *youtubeStreamProxy {
	return &youtubeStreamProxy{
		client: &http.Client{
			Transport: &http.Transport{
				DialContext: (&net.Dialer{Timeout: youtubeStreamDialTimeout}).DialContext,
				// 首响应头超时:连接建立但上游不吐头的兜底。
				ResponseHeaderTimeout: youtubeStreamDialTimeout,
				TLSHandshakeTimeout:   youtubeStreamDialTimeout,
				MaxIdleConnsPerHost:   4,
			},
		},
		entries: make(map[string]streamEntry),
	}
}

// register 登记一条直链并返回代理 token(upstream 为空时返回空串)。
func (p *youtubeStreamProxy) register(upstream string) string {
	if upstream == "" {
		return ""
	}
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		// crypto/rand 失败极罕见;降级为不上代理(保留原始直链,与旧行为一致)。
		return ""
	}
	token := hex.EncodeToString(raw)
	now := time.Now()
	p.mu.Lock()
	defer p.mu.Unlock()
	// 顺手清过期。
	kept := p.order[:0]
	for _, id := range p.order {
		if e, ok := p.entries[id]; ok && now.Before(e.expiresAt) {
			kept = append(kept, id)
		} else {
			delete(p.entries, id)
		}
	}
	p.order = kept
	// 容量淘汰:最旧优先。
	for len(p.order) >= youtubeStreamMaxEntries {
		oldest := p.order[len(p.order)-1]
		p.order = p.order[:len(p.order)-1]
		delete(p.entries, oldest)
	}
	p.entries[token] = streamEntry{
		upstream:  upstream,
		expiresAt: now.Add(youtubeStreamTokenTTL),
		addedAt:   now,
	}
	p.order = append([]string{token}, p.order...)
	return token
}

// lookup 返回 token 对应的直链;不存在/已过期返回空串。
func (p *youtubeStreamProxy) lookup(token string) string {
	if token == "" {
		return ""
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	entry, ok := p.entries[token]
	if !ok {
		return ""
	}
	if time.Now().After(entry.expiresAt) {
		delete(p.entries, token)
		for i, id := range p.order {
			if id == token {
				p.order = append(p.order[:i:i], p.order[i+1:]...)
				break
			}
		}
		return ""
	}
	return entry.upstream
}

// serve 透传一次播放流请求:转发 Range,流式回传字节。
// 客户端断开会通过 r.Context() 取消上游请求,无 goroutine 泄漏。
func (p *youtubeStreamProxy) serve(w http.ResponseWriter, r *http.Request, token string) {
	upstream := p.lookup(token)
	if upstream == "" {
		w.Header().Set("Cache-Control", "no-store")
		writeJSONStatus(w, http.StatusNotFound, map[string]string{"error": "stream unavailable or expired"})
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, upstream, nil)
	if err != nil {
		writeJSONStatus(w, http.StatusInternalServerError, map[string]string{"error": "bad upstream url"})
		return
	}
	// Range 是播放器拖动/续播的关键,原样透传。
	if rangeHeader := r.Header.Get("Range"); rangeHeader != "" {
		req.Header.Set("Range", rangeHeader)
	}
	req.Header.Set("User-Agent", bilibiliUserAgent)

	resp, err := p.client.Do(req)
	if err != nil {
		// 客户端断开导致的取消不写响应(连接已关闭)。
		if r.Context().Err() != nil {
			return
		}
		writeJSONStatus(w, http.StatusBadGateway, map[string]string{"error": "upstream fetch failed"})
		return
	}
	defer resp.Body.Close()

	// 透传播放器需要的响应头。
	for _, name := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if value := resp.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	// 保留上游状态码(200 全量 / 206 区间 / 上游 403 直链过期)。
	w.WriteHeader(resp.StatusCode)

	// 分块写出并主动 Flush:长视频流不能整包缓冲。
	buf := make([]byte, 64<<10)
	flusher, _ := w.(http.Flusher)
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, writeErr := w.Write(buf[:n]); writeErr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if readErr != nil {
			// 上游 EOF 是正常结束;其他读错中断即可,播放器会按 Range 重试。
			return
		}
	}
}

// rewriteForProxy 把 resolve 结果里的直链改写为代理 token 地址。
//
//   - HLS(m3u8)的分片地址也在 googlevideo 上且每段不同,逐段代理超出
//     本端点职责:代理模式下跳过 HLS 选项,保留可直连代理的直链选项。
//     全部为 HLS 时保持原样(反正无法播放,行为与改写前一致)。
//   - availableQualities 与 options 一一对应,同步过滤被丢弃的档位。
//   - 未配置公网基址(本地开发/未启用代理)时原样返回。
func (s *mediaServer) rewriteForProxy(result PlaybackResultResponse) PlaybackResultResponse {
	if s.yt == nil || s.yt.publicBase == "" || s.youtubeStream == nil {
		return result
	}
	rewritten := make([]PlaybackOptionResponse, 0, len(result.Options))
	keptQN := make(map[int]struct{}, len(result.Options))
	for _, opt := range result.Options {
		if strings.Contains(strings.ToLower(opt.MimeType), "mpegurl") {
			continue
		}
		if token := s.youtubeStream.register(opt.URL); token != "" {
			opt.URL = s.yt.publicBase + "/providers/youtube/stream/" + token
		}
		if opt.AudioURL != "" {
			if token := s.youtubeStream.register(opt.AudioURL); token != "" {
				opt.AudioURL = s.yt.publicBase + "/providers/youtube/stream/" + token
			}
		}
		rewritten = append(rewritten, opt)
		keptQN[opt.QN] = struct{}{}
	}
	if len(rewritten) == 0 {
		return result
	}
	qualities := make([]QualityOptionResponse, 0, len(result.AvailableQualities))
	for _, q := range result.AvailableQualities {
		if _, ok := keptQN[q.QN]; ok {
			qualities = append(qualities, q)
		}
	}
	result.Options = rewritten
	result.AvailableQualities = qualities
	return result
}

// handleYouTubeStream 实现 GET /api/providers/youtube/stream/{token}
// (由 ServeHTTP 直接分发以绕过 apiTimeoutHandler,见 main.go)。
func (s *mediaServer) handleYouTubeStream(w http.ResponseWriter, r *http.Request, token string) {
	if s.youtubeStream == nil {
		w.Header().Set("Cache-Control", "no-store")
		writeJSONStatus(w, http.StatusServiceUnavailable, map[string]string{"error": "stream proxy disabled"})
		return
	}
	s.youtubeStream.serve(w, r, token)
}
