package main

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// bilibili_search.go — B 站**全站**实时搜索通道。
//
// **为什么需要**:此前 App 的 B 站搜索走的是 /api/search，那是**已审核目录内的**
// 关键词过滤(catalog 里 approved 的视频做子串匹配)。用户想看「B 站上的某个
// 视频」，而目录只收录了经人工审核的一小部分，于是绝大多数搜索都搜不到——
// 这不是搜索坏了，而是搜索范围本来就不对。
//
// 本文件提供真正的全站搜索:调用 B 站官方公开搜索接口
// GET /x/web-interface/wbi/search/type(用户在网页/客户端里用的是同一个)，
// 按关键词返回视频列表。结果**不写 catalog**(与 youtube 实时通道同一受控例外):
// 仅供即时展示与播放，不影响审核体系。
//
// **鉴权与限流**:与 youtube 实时端点一致，要求 X-App-Token 并做 per-IP 限流——
// 每次调用都是一次真实上游请求，不能暴露给匿名流量。
const (
	// B 站搜索 per-IP 每分钟上限:不拉起子进程、响应快，比 youtube 宽松；
	// 但仍是真实上游调用，正常遥控器操作(一次搜索 = 1 次调用)绰绰有余。
	bilibiliSearchPerMinuteLimit = 20
	// 单页条数:B 站接口 video 类型上限 20/页，TV 网格一屏用足即可。
	bilibiliSearchPageSize = 20
	// 搜索请求超时:该接口正常 200~500ms，给 8s 余量(弱网 + CF 回源)。
	bilibiliSearchTimeout = 8 * time.Second
	// 搜索结果缓存上限与 TTL:避免用户来回翻同页/重搜时反复打上游。
	bilibiliSearchCacheMax = 128
	bilibiliSearchCacheTTL = 5 * time.Minute
)

// B 站官方 Web 接口基址与必需的请求头。URL 用 var:单测覆盖成假上游。
var (
	bilibiliNavURL    = "https://api.bilibili.com/x/web-interface/nav"
	bilibiliSearchURL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
)

// B 站要求带浏览器 UA + Referer，否则触发风控/403。
const (
	bilibiliUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
	bilibiliReferer = "https://www.bilibili.com"
)

// wbiMixinKeyTable 是 B 站 WBI 签名用的固定置换表(官方前端硬编码)。
// 取 nav 下发的 img_key + sub_key 拼接后按该表重排，取前 32 位即 mixin key。
var wbiMixinKeyTable = [64]int{
	46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
	27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
	37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
	22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
}

// bilibiliSearchClient 负责 WBI 密钥缓存与搜索调用。
// 密钥有效期按 B 站约定取 10 分钟(与 App 端 BilibiliApi 一致)。
type bilibiliSearchClient struct {
	http *http.Client

	mu         sync.Mutex
	mixinKey   string
	keyExpires time.Time

	cacheMu sync.Mutex
	cache   map[string]bilibiliSearchCacheEntry
}

type bilibiliSearchCacheEntry struct {
	items     []VideoSummary
	total     int64
	expiresAt time.Time
	addedAt   time.Time
}

// defaultBilibiliSearchLimiter 是进程级默认防线:main() 构造时已显式接线，
// 这里仅作 nil 兜底(测试裸构造 mediaServer 时行为与生产一致)。
var defaultBilibiliSearchLimiter = newIPWindowLimiter(bilibiliSearchPerMinuteLimit, time.Minute)

func newBilibiliSearchClient() *bilibiliSearchClient {
	return &bilibiliSearchClient{
		http:  &http.Client{Timeout: bilibiliSearchTimeout},
		cache: make(map[string]bilibiliSearchCacheEntry),
	}
}

// bilibiliNavPayload 用于从 nav 接口提取 WBI 密钥。
type bilibiliNavPayload struct {
	Data struct {
		WbiImg struct {
			ImgURL string `json:"img_url"`
			SubURL string `json:"sub_url"`
		} `json:"wbi_img"`
	} `json:"data"`
}

// bilibiliSearchPayload 是 B 站搜索接口的响应。
type bilibiliSearchPayload struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    struct {
		NumResults int64 `json:"numResults"`
		Result     []struct {
			BVID     string `json:"bvid"`
			Title    string `json:"title"`
			Author   string `json:"author"`
			Duration string `json:"duration"`
			Pic      string `json:"pic"`
			Desc     string `json:"description"`
		} `json:"result"`
	} `json:"data"`
}

// ensureMixinKey 返回 WBI mixin key，过期则重新拉 nav。
func (c *bilibiliSearchClient) ensureMixinKey(ctx context.Context) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.mixinKey != "" && time.Now().Before(c.keyExpires) {
		return c.mixinKey, nil
	}
	raw, err := c.getJSON(ctx, bilibiliNavURL, nil)
	if err != nil {
		return "", fmt.Errorf("nav: %w", err)
	}
	var payload bilibiliNavPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return "", fmt.Errorf("decode nav: %w", err)
	}
	imgKey := wbiKeyFromURL(payload.Data.WbiImg.ImgURL)
	subKey := wbiKeyFromURL(payload.Data.WbiImg.SubURL)
	if imgKey == "" || subKey == "" {
		return "", fmt.Errorf("nav returned empty wbi keys")
	}
	c.mixinKey = wbiMixinKey(imgKey, subKey)
	// 保守取 10 分钟:官方未公布精确有效期，过长会在密钥轮换后签名失败。
	c.keyExpires = time.Now().Add(10 * time.Minute)
	return c.mixinKey, nil
}

// wbiKeyFromURL 从 ".../<key>.png" 里取 key。
func wbiKeyFromURL(rawURL string) string {
	base := rawURL
	if index := strings.LastIndex(base, "/"); index >= 0 {
		base = base[index+1:]
	}
	if index := strings.Index(base, "."); index >= 0 {
		base = base[:index]
	}
	return strings.TrimSpace(base)
}

// wbiMixinKey 按固定置换表从 img_key+sub_key 生成 32 位 mixin key。
func wbiMixinKey(imgKey, subKey string) string {
	origin := imgKey + subKey
	builder := strings.Builder{}
	builder.Grow(32)
	for _, index := range wbiMixinKeyTable {
		if index < len(origin) {
			builder.WriteByte(origin[index])
		}
	}
	key := builder.String()
	if len(key) > 32 {
		key = key[:32]
	}
	return key
}

// signWbi 给参数补 wts + w_rid(WBI 签名)。
func signWbi(params map[string]string, mixinKey string) map[string]string {
	signed := make(map[string]string, len(params)+2)
	for key, value := range params {
		signed[key] = value
	}
	signed["wts"] = fmt.Sprintf("%d", time.Now().Unix())

	keys := make([]string, 0, len(signed))
	for key := range signed {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	filter := strings.NewReplacer("!", "", "'", "", "(", "", ")", "", "*", "")
	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		// B 站要求先过滤这些字符，再做 URL 编码参与签名。
		value := filter.Replace(signed[key])
		parts = append(parts, url.QueryEscape(key)+"="+url.QueryEscape(value))
	}
	sum := md5.Sum([]byte(strings.Join(parts, "&") + mixinKey))
	signed["w_rid"] = hex.EncodeToString(sum[:])
	return signed
}

// getJSON 发一个带浏览器头的 GET 并返回响应体(上限 2MiB)。
func (c *bilibiliSearchClient) getJSON(ctx context.Context, target string, params map[string]string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	if len(params) > 0 {
		query := req.URL.Query()
		for key, value := range params {
			query.Set(key, value)
		}
		req.URL.RawQuery = query.Encode()
	}
	req.Header.Set("User-Agent", bilibiliUserAgent)
	req.Header.Set("Referer", bilibiliReferer)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 2<<20))
}

// searchVideos 按关键词全站搜索 B 站视频(单页)。
func (c *bilibiliSearchClient) searchVideos(ctx context.Context, query string, page int) ([]VideoSummary, int64, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return []VideoSummary{}, 0, nil
	}
	if page < 1 {
		page = 1
	}
	cacheKey := fmt.Sprintf("%s#%d", strings.ToLower(query), page)
	if items, total, ok := c.cacheGet(cacheKey); ok {
		return items, total, nil
	}

	mixinKey, err := c.ensureMixinKey(ctx)
	if err != nil {
		return nil, 0, err
	}
	params := signWbi(map[string]string{
		"keyword":     query,
		"search_type": "video",
		"page":        strconv.Itoa(page),
		"page_size":   strconv.Itoa(bilibiliSearchPageSize),
	}, mixinKey)
	raw, err := c.getJSON(ctx, bilibiliSearchURL, params)
	if err != nil {
		return nil, 0, err
	}
	var payload bilibiliSearchPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return nil, 0, fmt.Errorf("decode search: %w", err)
	}
	if payload.Code != 0 {
		return nil, 0, fmt.Errorf("bilibili search code=%d msg=%s", payload.Code, payload.Message)
	}
	items := make([]VideoSummary, 0, len(payload.Data.Result))
	for _, entry := range payload.Data.Result {
		bvid := strings.TrimSpace(entry.BVID)
		if bvid == "" {
			continue
		}
		items = append(items, VideoSummary{
			ID:                bvid,
			Title:             cleanBilibiliSearchTitle(entry.Title, entry.Author),
			Description:       strings.TrimSpace(entry.Desc),
			ThumbnailURL:      upgradeToHTTPS(entry.Pic),
			ThumbnailURLLarge: upgradeToHTTPS(entry.Pic),
			DurationSeconds:   parseBilibiliDuration(entry.Duration),
		})
	}
	total := payload.Data.NumResults
	if total <= 0 {
		total = int64(len(items))
	}
	c.cachePut(cacheKey, items, total)
	return items, total, nil
}

// cleanBilibiliSearchTitle 去掉搜索接口返回的高亮标签，并补上作者前缀
// (与 youtube 实时搜索「频道: 标题」的展示口径一致)。
func cleanBilibiliSearchTitle(title, author string) string {
	cleaned := strings.TrimSpace(title)
	cleaned = strings.ReplaceAll(cleaned, "<em class=\"keyword\">", "")
	cleaned = strings.ReplaceAll(cleaned, "</em>", "")
	cleaned = htmlUnescapeLite(cleaned)
	author = strings.TrimSpace(author)
	if author != "" {
		return author + ": " + cleaned
	}
	return cleaned
}

// htmlUnescapeLite 还原标题里最常见的几个 HTML 实体。
func htmlUnescapeLite(value string) string {
	replacer := strings.NewReplacer(
		"&amp;", "&",
		"&quot;", "\"",
		"&#39;", "'",
		"&lt;", "<",
		"&gt;", ">",
	)
	return replacer.Replace(value)
}

// parseBilibiliDuration 把 "15:53" / "1358:52" 解析成秒。
func parseBilibiliDuration(value string) int64 {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0
	}
	var seconds int64
	for _, part := range strings.Split(value, ":") {
		part = strings.TrimSpace(part)
		if part == "" {
			return 0
		}
		number := int64(0)
		for _, ch := range part {
			if ch < '0' || ch > '9' {
				return 0
			}
			number = number*10 + int64(ch-'0')
		}
		seconds = seconds*60 + number
	}
	return seconds
}

// upgradeToHTTPS 把 //i0.hdslb.com/... 或 http:// 升成 https://(Android 明文策略)。
func upgradeToHTTPS(raw string) string {
	raw = strings.TrimSpace(raw)
	switch {
	case strings.HasPrefix(raw, "//"):
		return "https:" + raw
	case strings.HasPrefix(raw, "http://"):
		return "https://" + strings.TrimPrefix(raw, "http://")
	default:
		return raw
	}
}

func (c *bilibiliSearchClient) cacheGet(key string) ([]VideoSummary, int64, bool) {
	c.cacheMu.Lock()
	defer c.cacheMu.Unlock()
	entry, ok := c.cache[key]
	if !ok {
		return nil, 0, false
	}
	if time.Now().After(entry.expiresAt) {
		delete(c.cache, key)
		return nil, 0, false
	}
	return entry.items, entry.total, true
}

func (c *bilibiliSearchClient) cachePut(key string, items []VideoSummary, total int64) {
	c.cacheMu.Lock()
	defer c.cacheMu.Unlock()
	now := time.Now()
	if len(c.cache) >= bilibiliSearchCacheMax {
		for id, entry := range c.cache {
			if now.After(entry.expiresAt) {
				delete(c.cache, id)
			}
		}
		for len(c.cache) >= bilibiliSearchCacheMax {
			var oldestKey string
			var oldest time.Time
			first := true
			for id, entry := range c.cache {
				if first || entry.addedAt.Before(oldest) {
					oldestKey, oldest, first = id, entry.addedAt, false
				}
			}
			delete(c.cache, oldestKey)
		}
	}
	c.cache[key] = bilibiliSearchCacheEntry{
		items:     items,
		total:     total,
		expiresAt: now.Add(bilibiliSearchCacheTTL),
		addedAt:   now,
	}
}

// handleBilibiliSearch 实现 GET /api/providers/bilibili/search?q=&page=
//
// 全站实时搜索(结果不写 catalog):App 的 B 站搜索走这里，而不是目录过滤。
// 鉴权与 youtube 实时端点一致(必须有 token)，避免被当成公开搜索代理。
func (s *mediaServer) handleBilibiliSearch(w http.ResponseWriter, r *http.Request) {
	if !s.requireAppToken(w, r) {
		return
	}
	if s.bilibiliSearch == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "NOT_CONFIGURED", Message: "服务端未启用 B 站搜索。"})
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	page := 1
	if raw := strings.TrimSpace(r.URL.Query().Get("page")); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			page = parsed
		}
	}
	// 空关键词直接返回空结果(与 youtube 搜索端点一致)，不消耗配额。
	if query == "" {
		w.Header().Set("Cache-Control", "public, max-age=30")
		s.writeJSON(w, http.StatusOK, SearchResponse{Query: "", Total: 0, Items: []VideoSummary{}})
		return
	}
	if len(query) > maxSearchQueryBytes {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "QUERY_TOO_LONG", Message: "搜索关键词过长。"})
		return
	}
	// 该端点要求 token，但仍是真实上游调用，做 per-IP 限流。
	limiter := s.bilibiliSearchLimiter
	if limiter == nil {
		limiter = defaultBilibiliSearchLimiter
	}
	if !limiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "搜索过于频繁，请稍后再试。"})
		return
	}
	items, total, err := s.bilibiliSearch.searchVideos(r.Context(), query, page)
	if err != nil {
		log.Printf("bilibili search %q page=%d: %v", query, page, err)
		s.writeError(w, &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_ERROR",
			Message: "B 站搜索暂不可用，请稍后再试。",
		})
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=30")
	s.writeJSON(w, http.StatusOK, SearchResponse{Query: query, Total: total, Items: items})
}
