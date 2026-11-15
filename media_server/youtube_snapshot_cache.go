package main

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"
)

// YouTube 实时通道的进程内缓存与后台预热。
//
// 背景:分类浏览与搜索每次都要拉起 yt-dlp(vod_extractor 并发上限 2;冷启动受
// Deno 签名/PO Token 影响可达数十秒)。把"近期可能再次请求"的结果缓存并后台
// 轮换刷新,用户路径就能从"秒级等待"变成"毫秒命中",同时避免分类页把播放解析的
// 并发槽占满。
const (
	youtubeSearchCacheTTL   = 6 * time.Minute
	youtubeCategoryCacheTTL = 12 * time.Minute
	youtubeResultCacheMax   = 128
	youtubeWarmInterval     = 20 * time.Second
	youtubeWarmTimeout      = 40 * time.Second
)

// defaultYouTubeHotKeywords 对应 media_app SearchScreen.kt 的 YOUTUBE_HOT_KEYWORDS,
// 用于启动后预热,让"点热词即出结果"。列表漂移只会降低预热命中率,不影响功能。
var defaultYouTubeHotKeywords = []string{
	"音乐", "纪录片", "科技", "旅行", "美食", "学习", "游戏", "电影",
}

type youtubeCacheEntry struct {
	items     []VideoSummary
	expiresAt time.Time
	addedAt   time.Time
}

// youtubeResultCache 是按 key 的 TTL 缓存,容量有上限(视频列表 KB 级,128 条足够覆盖
// 真实浏览模式;容器内存只有 64M,不能无界增长)。
type youtubeResultCache struct {
	mu      sync.Mutex
	max     int
	ttl     time.Duration
	entries map[string]youtubeCacheEntry
}

func newYouTubeResultCache(max int, ttl time.Duration) *youtubeResultCache {
	return &youtubeResultCache{max: max, ttl: ttl, entries: make(map[string]youtubeCacheEntry)}
}

func (c *youtubeResultCache) get(key string) ([]VideoSummary, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	if !ok {
		return nil, false
	}
	if time.Now().After(entry.expiresAt) {
		delete(c.entries, key)
		return nil, false
	}
	return entry.items, true
}

func (c *youtubeResultCache) put(key string, items []VideoSummary) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	if len(c.entries) >= c.max {
		for id, entry := range c.entries {
			if now.After(entry.expiresAt) {
				delete(c.entries, id)
			}
		}
		for len(c.entries) >= c.max {
			var oldestKey string
			var oldest time.Time
			first := true
			for id, entry := range c.entries {
				if first || entry.addedAt.Before(oldest) {
					oldestKey, oldest, first = id, entry.addedAt, false
				}
			}
			delete(c.entries, oldestKey)
		}
	}
	c.entries[key] = youtubeCacheEntry{items: items, expiresAt: now.Add(c.ttl), addedAt: now}
}

func (c *youtubeResultCache) size() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.entries)
}

type youtubeCacheFlight struct {
	done  chan struct{}
	items []VideoSummary
	err   error
}

// youtubeFlightGroup 让同一 key 的并发请求共享一次上游调用(分类页多端同时打开/
// App 重试时,不会各自拉起 yt-dlp)。
type youtubeFlightGroup struct {
	mu    sync.Mutex
	calls map[string]*youtubeCacheFlight
}

func newYouTubeFlightGroup() *youtubeFlightGroup {
	return &youtubeFlightGroup{calls: make(map[string]*youtubeCacheFlight)}
}

func (g *youtubeFlightGroup) do(ctx context.Context, key string, fn func(context.Context) ([]VideoSummary, error)) ([]VideoSummary, error) {
	g.mu.Lock()
	if flight, ok := g.calls[key]; ok {
		g.mu.Unlock()
		select {
		case <-flight.done:
			return flight.items, flight.err
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	flight := &youtubeCacheFlight{done: make(chan struct{})}
	g.calls[key] = flight
	g.mu.Unlock()

	defer func() {
		if r := recover(); r != nil {
			flight.err = fmt.Errorf("youtube cache flight panic: %v", r)
		}
		g.mu.Lock()
		delete(g.calls, key)
		g.mu.Unlock()
		close(flight.done)
	}()
	flight.items, flight.err = fn(ctx)
	return flight.items, flight.err
}

// ---- mediaServer 上的缓存入口 ----

func (s *mediaServer) ensureYouTubeCaches() {
	s.youtubeCacheOnce.Do(func() {
		s.youtubeSearchCache = newYouTubeResultCache(youtubeResultCacheMax, youtubeSearchCacheTTL)
		s.youtubeSearchGroup = newYouTubeFlightGroup()
		s.youtubeCategoryCache = newYouTubeResultCache(len(defaultYouTubeCategories)*2, youtubeCategoryCacheTTL)
		s.youtubeCategoryGroup = newYouTubeFlightGroup()
	})
}

func normalizeYouTubeCacheKey(query string) string {
	return strings.ToLower(strings.TrimSpace(query))
}

// cachedYouTubeSearch 返回搜索结果:缓存命中直接返回,未命中走单飞 + 上游。
func (s *mediaServer) cachedYouTubeSearch(ctx context.Context, query string) ([]VideoSummary, error) {
	s.ensureYouTubeCaches()
	key := normalizeYouTubeCacheKey(query)
	if items, ok := s.youtubeSearchCache.get(key); ok {
		return items, nil
	}
	return s.youtubeSearchGroup.do(ctx, key, func(ctx context.Context) ([]VideoSummary, error) {
		items, err := s.yt.searchVideos(ctx, query)
		if err != nil {
			return nil, err
		}
		s.youtubeSearchCache.put(key, items)
		return items, nil
	})
}

// cachedYouTubeCategory 返回分类快照(分类 ID → 最近一次 ytsearch 结果)。
func (s *mediaServer) cachedYouTubeCategory(ctx context.Context, category youtubeCategory) ([]VideoSummary, error) {
	s.ensureYouTubeCaches()
	if items, ok := s.youtubeCategoryCache.get(category.ID); ok {
		return items, nil
	}
	return s.youtubeCategoryGroup.do(ctx, category.ID, func(ctx context.Context) ([]VideoSummary, error) {
		items, err := s.yt.searchVideos(ctx, category.Query)
		if err != nil {
			return nil, err
		}
		s.youtubeCategoryCache.put(category.ID, items)
		return items, nil
	})
}

type youtubeWarmTarget struct {
	category youtubeCategory
	query    string
}

func (t youtubeWarmTarget) displayName() string {
	if t.category.ID != "" {
		return "category:" + t.category.ID
	}
	return t.query
}

// startYouTubeCacheWarmer 后台轮流预热 6 个内置分类与 YouTube 热词:
// 每 youtubeWarmInterval 只刷新一个目标,用户请求到达时缓存已热;单目标失败只记
// 日志、下一轮重试。只有配置了 vod_extractor 时才启动。
func (s *mediaServer) startYouTubeCacheWarmer() {
	if !s.youtubeRealtimeEnabled() {
		return
	}
	s.ensureYouTubeCaches()
	targets := make([]youtubeWarmTarget, 0, len(defaultYouTubeCategories)+len(defaultYouTubeHotKeywords))
	for _, category := range defaultYouTubeCategories {
		targets = append(targets, youtubeWarmTarget{category: category})
	}
	for _, keyword := range defaultYouTubeHotKeywords {
		targets = append(targets, youtubeWarmTarget{query: keyword})
	}
	if len(targets) == 0 {
		return
	}
	go func() {
		for index := 0; ; index = (index + 1) % len(targets) {
			target := targets[index]
			ctx, cancel := context.WithTimeout(context.Background(), youtubeWarmTimeout)
			var err error
			if target.category.ID != "" {
				_, err = s.cachedYouTubeCategory(ctx, target.category)
			} else {
				_, err = s.cachedYouTubeSearch(ctx, target.query)
			}
			cancel()
			if err != nil {
				log.Printf("youtube cache warm %q: %v", target.displayName(), err)
			}
			time.Sleep(youtubeWarmInterval)
		}
	}()
}
