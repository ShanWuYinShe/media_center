package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// youtube_thumb.go — YouTube 缩略图代理。
//
// **为什么需要**:国内直连 i.ytimg.com 实测 100% 不可达(联通线路 5/5 超时),
// YouTube 实时搜索/分类下发的是 i.ytimg.com 原始地址,电视上封面全部裂图。
// 服务端(境外)到 Google 只要 ~100ms,由服务端代取图片并改写下发 URL 即可,
// **App 无需发版**——normal/legacy 的图片加载逻辑原样可用。
//
// **URL 形态**:GET /api/v1/providers/youtube/thumb/{videoID}/{quality}.jpg
// 固定形态、不代理任意 URL(不是开放代理);videoID 严格校验 11 字符格式。
// 以 .jpg 结尾让 Cloudflare 默认静态扩展缓存生效,配合下发 Cache-Control
// public/immutable,同一封面在边缘与客户端只回源一次。
//
// **内存占用**:media_server 容器只有 64M(GOMEMLIMIT=48MiB),LRU 上限
// 96 条、单张上限 1MiB,hq/sd 通常 10~30KB,maxres 可达 100~300KB,
// 最坏 ~20MiB 仍留余量;命中率不足时只是多回源,不影响正确性。
const (
	youtubeThumbMaxBytes    = 1 << 20
	youtubeThumbFetchTimout = 6 * time.Second
	youtubeThumbCacheMax    = 96
	// 客户端/边缘缓存 7 天:缩略图内容按 videoID 基本不变。
	youtubeThumbClientCacheSeconds = 7 * 24 * 3600

	youtubeThumbQualityHQ     = "hq"
	youtubeThumbQualitySD     = "sd"
	youtubeThumbQualityMaxres = "maxres"
)

// youtubeThumbUpstreamBase 可被单测覆盖(默认指向真实 i.ytimg.com)。
var youtubeThumbUpstreamBase = "https://i.ytimg.com/vi/"

// 每分钟每 IP 上限:正常 TV 浏览一屏 ~20 张封面,150/min 足够两台电视并行
// 翻页;CF 边缘缓存吸收重复请求后,只有新 ID 才回源。
const youtubeThumbPerMinuteLimit = 150

// defaultYouTubeThumbLimiter 是进程级默认防线:main() 构造时已显式接线,
// 这里仅作 nil 兜底(测试裸构造 mediaServer 时行为与生产一致)。
var defaultYouTubeThumbLimiter = newIPWindowLimiter(youtubeThumbPerMinuteLimit, time.Minute)

// qualityCandidates 返回该清晰度的取图候选序列(前面的 404 时依次回退;
// hqdefault 对所有视频都存在,兜底必成)。
func qualityCandidates(quality string) []string {
	switch quality {
	case youtubeThumbQualityMaxres:
		return []string{"maxresdefault.jpg", "sddefault.jpg", "hqdefault.jpg"}
	case youtubeThumbQualitySD:
		return []string{"sddefault.jpg", "hqdefault.jpg"}
	case youtubeThumbQualityHQ:
		return []string{"hqdefault.jpg"}
	default:
		return nil
	}
}

// isValidYouTubeThumbQuality 校验 quality 文件名(形如 hq.jpg)。
func isValidYouTubeThumbQuality(file string) (string, bool) {
	name := strings.TrimSuffix(file, ".jpg")
	if name == file || name == "" {
		// 没有 .jpg 后缀或纯 .jpg:拒绝(后缀是 CF 静态缓存生效的关键)。
		return "", false
	}
	switch name {
	case youtubeThumbQualityHQ, youtubeThumbQualitySD, youtubeThumbQualityMaxres:
		return name, true
	default:
		return "", false
	}
}

// thumbCacheEntry 是一张已取回的图片。
type thumbCacheEntry struct {
	data      []byte
	fetchedAt time.Time
}

// youtubeThumbCache 是固定容量的 LRU(非并发安全,由持有方加锁)。
type youtubeThumbCache struct {
	entries map[string]thumbCacheEntry
	order   []string // 按最近使用排序,尾部最旧
	max     int
}

func newYouTubeThumbCache(max int) *youtubeThumbCache {
	return &youtubeThumbCache{entries: make(map[string]thumbCacheEntry), max: max}
}

func (c *youtubeThumbCache) get(key string) ([]byte, bool) {
	data, ok := c.entries[key]
	if !ok {
		return nil, false
	}
	// 移到最新位置。
	c.touch(key)
	return data.data, true
}

func (c *youtubeThumbCache) put(key string, data []byte) {
	if _, ok := c.entries[key]; ok {
		c.entries[key] = thumbCacheEntry{data: data, fetchedAt: time.Now()}
		c.touch(key)
		return
	}
	c.entries[key] = thumbCacheEntry{data: data, fetchedAt: time.Now()}
	c.order = append([]string{key}, c.order...)
	for len(c.order) > c.max {
		oldest := c.order[len(c.order)-1]
		c.order = c.order[:len(c.order)-1]
		delete(c.entries, oldest)
	}
}

func (c *youtubeThumbCache) touch(key string) {
	for i, id := range c.order {
		if id == key {
			c.order = append([]string{key}, append(c.order[:i:i], c.order[i+1:]...)...)
			return
		}
	}
}

// youtubeThumbProxy 负责取图与缓存。内存中保存原始字节,响应直接写出。
type youtubeThumbProxy struct {
	client *http.Client

	mu    sync.Mutex
	cache *youtubeThumbCache
}

func newYouTubeThumbProxy() *youtubeThumbProxy {
	return &youtubeThumbProxy{
		client: &http.Client{Timeout: youtubeThumbFetchTimout},
		cache:  newYouTubeThumbCache(youtubeThumbCacheMax),
	}
}

// fetch 取回第一个可用的候选图(404/超时则试下一个;全失败返回 nil)。
func (p *youtubeThumbProxy) fetch(ctx context.Context, videoID string, quality string) []byte {
	for _, file := range qualityCandidates(quality) {
		if file == "" {
			continue
		}
		target := youtubeThumbUpstreamBase + videoID + "/" + file
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		if err != nil {
			return nil
		}
		// B 站同款浏览器头即可,YouTube 静态图不校验 Referer。
		req.Header.Set("User-Agent", bilibiliUserAgent)
		resp, err := p.client.Do(req)
		if err != nil {
			continue
		}
		data, readErr := io.ReadAll(io.LimitReader(resp.Body, youtubeThumbMaxBytes))
		resp.Body.Close()
		if readErr != nil || resp.StatusCode != http.StatusOK || len(data) == 0 {
			// 404/空:试下一个候选。
			continue
		}
		return data
	}
	return nil
}

// serve 完成一次缩略图代理响应。
func (p *youtubeThumbProxy) serve(w http.ResponseWriter, r *http.Request, videoID, quality string) {
	key := videoID + "/" + quality
	p.mu.Lock()
	data, ok := p.cache.get(key)
	p.mu.Unlock()
	if ok {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", fmt.Sprintf("public, max-age=%d, immutable", youtubeThumbClientCacheSeconds))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(data)
		return
	}

	data = p.fetch(r.Context(), videoID, quality)
	if data == nil {
		// 全候选失败:短暂 no-store,避免把失败钉在边缘缓存上。
		w.Header().Set("Cache-Control", "no-store")
		writeJSONStatus(w, http.StatusNotFound, map[string]string{"error": "thumbnail unavailable"})
		return
	}
	p.mu.Lock()
	p.cache.put(key, data)
	p.mu.Unlock()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", fmt.Sprintf("public, max-age=%d, immutable", youtubeThumbClientCacheSeconds))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// youtubeThumbURL 生成客户端可访问的代理地址(publicBase 为空时返回原始地址,
// 即本地开发/未配置公网基址时保持直连行为)。
func youtubeThumbURL(publicBase, videoID, quality string) string {
	if publicBase == "" {
		return ""
	}
	return fmt.Sprintf("%s/providers/youtube/thumb/%s/%s.jpg", publicBase, videoID, quality)
}

// handleYouTubeThumb 实现 GET /api/providers/youtube/thumb/{videoID}/{quality}.jpg
//
// 公开图片、无鉴权(URL 形态固定,不是开放代理);Responses 可被 CF/客户端长缓存。
// 带 per-IP 限流:每个 miss 都是一次服务端到 googlevideo 的回源,无限流会把本端点
// 当成免费图片 CDN 刷爆带宽(CF 只缓存被请求过的图,新 ID 全部回源)。
func (s *mediaServer) handleYouTubeThumb(w http.ResponseWriter, r *http.Request, videoID, file string) {
	quality, ok := isValidYouTubeThumbQuality(file)
	if !ok || !isValidYouTubeVideoID(videoID) {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "缩略图地址不合法。"})
		return
	}
	if s.youtubeThumb == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未启用缩略图代理。"})
		return
	}
	limiter := s.youtubeThumbLimiter
	if limiter == nil {
		limiter = defaultYouTubeThumbLimiter
	}
	if !limiter.allow(clientIPFromRequest(r)) {
		w.Header().Set("Retry-After", "60")
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "请求过于频繁,请稍后再试。"})
		return
	}
	s.youtubeThumb.serve(w, r, videoID, quality)
}
