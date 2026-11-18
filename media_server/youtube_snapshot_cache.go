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
	youtubeWarmTimeout      = 40 * time.Second
	// 预热在 TTL 的 60% 处强制刷新。旧实现走缓存读取路径,命中即返回、不续期
	// 也不打上游:一旦 TTL 到点而预热还没轮到该 key,用户请求就要等一次完整
	// ytsearch(实测 7s,慢时 20~30s),表现为"有时秒出、有时转很久"。
	youtubeSearchWarmInterval   = youtubeSearchCacheTTL * 3 / 5
	youtubeCategoryWarmInterval = youtubeCategoryCacheTTL * 3 / 5
	// 预热调度粒度与启动错峰:避免进程启动瞬间 14 个目标同时打上游。
	youtubeWarmTick           = 5 * time.Second
	youtubeWarmStartupStagger = 8 * time.Second
	// 预热失败后的重试间隔(短于正常刷新间隔,尽快恢复热缓存)。
	youtubeWarmRetryDelay = 30 * time.Second
	// 单飞上游调用的兜底超时:与 leader 的请求取消解耦后,必须有独立上限,
	// 否则 leader 的客户端断开会让上游调用无限期存活。
	youtubeFlightTimeout = 45 * time.Second
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

func (g *youtubeFlightGroup) do(ctx context.Context, key string, fn func(context.Context) ([]VideoSummary, error)) (items []VideoSummary, err error) {
	g.mu.Lock()
	if flight, ok := g.calls[key]; ok {
		g.mu.Unlock()
		select {
		case <-flight.done:
			return flight.items, flight.err
		case <-ctx.Done():
			// 等待者按自己的 ctx 放弃;leader 仍在跑,结果供其他等待者复用。
			return nil, ctx.Err()
		}
	}
	flight := &youtubeCacheFlight{done: make(chan struct{})}
	g.calls[key] = flight
	g.mu.Unlock()

	// 上游调用必须脱离 leader 的请求取消:leader 退出分类页/断网时
	// r.Context() 被取消,若直接透传,同 key 的其它等待者会一起收到
	// context canceled(表现为随机 502),与 resolve 单飞的处理保持一致。
	upstreamCtx, cancelUpstream := context.WithTimeout(
		context.WithoutCancel(ctx), youtubeFlightTimeout,
	)
	defer cancelUpstream()

	// 命名返回值 + defer 兜底:fn panic 时 leader 也必须拿到 error。
	// 旧实现只在 defer 里设置 flight.err 却先 return 了零值 (nil,nil),
	// 调用方会把"空结果"当成成功返回 200。
	defer func() {
		if r := recover(); r != nil {
			flight.items, flight.err = nil, fmt.Errorf("youtube cache flight panic: %v", r)
		}
		g.mu.Lock()
		delete(g.calls, key)
		g.mu.Unlock()
		close(flight.done)
		items, err = flight.items, flight.err
	}()
	flight.items, flight.err = fn(upstreamCtx)
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
		s.cacheStats.searchHits.Add(1)
		return items, nil
	}
	return s.youtubeSearchGroup.do(ctx, key, func(ctx context.Context) ([]VideoSummary, error) {
		startedAt := time.Now()
		items, err := s.yt.searchVideos(ctx, query)
		s.cacheStats.recordSearchUpstream(startedAt)
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
		s.cacheStats.categoryHits.Add(1)
		return items, nil
	}
	return s.youtubeCategoryGroup.do(ctx, category.ID, func(ctx context.Context) ([]VideoSummary, error) {
		startedAt := time.Now()
		items, err := s.yt.searchVideos(ctx, category.Query)
		s.cacheStats.recordSearchUpstream(startedAt)
		if err != nil {
			return nil, err
		}
		s.youtubeCategoryCache.put(category.ID, items)
		return items, nil
	})
}

// peekYouTubeSearch 只读缓存(不打上游、不计限流):handler 用它判断"是否
// 必须消耗 per-IP 配额",避免缓存命中也被限流拒绝。
func (s *mediaServer) peekYouTubeSearch(query string) ([]VideoSummary, bool) {
	s.ensureYouTubeCaches()
	return s.youtubeSearchCache.get(normalizeYouTubeCacheKey(query))
}

// peekYouTubeCategory 同 peekYouTubeSearch,用于分类快照。
func (s *mediaServer) peekYouTubeCategory(category youtubeCategory) ([]VideoSummary, bool) {
	s.ensureYouTubeCaches()
	return s.youtubeCategoryCache.get(category.ID)
}

// refreshYouTubeSearch 强制打上游并回填缓存(预热专用):不走 get 短路,
// 否则预热永远命中旧项、TTL 到点后用户请求仍要等一次完整 ytsearch。
// 与用户路径共用单飞:若同一 key 已有用户请求在飞则直接加入,不重复打上游。
func (s *mediaServer) refreshYouTubeSearch(ctx context.Context, query string) ([]VideoSummary, error) {
	s.ensureYouTubeCaches()
	key := normalizeYouTubeCacheKey(query)
	return s.youtubeSearchGroup.do(ctx, key, func(ctx context.Context) ([]VideoSummary, error) {
		startedAt := time.Now()
		items, err := s.yt.searchVideos(ctx, query)
		s.cacheStats.recordSearchUpstream(startedAt)
		if err != nil {
			return nil, err
		}
		s.youtubeSearchCache.put(key, items)
		// 只有真正完成一次上游调用的 leader 才计数:预热命中旧缓存/加入用户
		// 在飞请求都不算"预热跑了一轮"(旧实现在缓存命中时也 +1,指标失真)。
		s.cacheStats.warmRuns.Add(1)
		return items, nil
	})
}

// refreshYouTubeCategory 同 refreshYouTubeSearch,用于 YouTube 内置分类快照。
func (s *mediaServer) refreshYouTubeCategory(ctx context.Context, category youtubeCategory) ([]VideoSummary, error) {
	s.ensureYouTubeCaches()
	return s.youtubeCategoryGroup.do(ctx, category.ID, func(ctx context.Context) ([]VideoSummary, error) {
		startedAt := time.Now()
		items, err := s.yt.searchVideos(ctx, category.Query)
		s.cacheStats.recordSearchUpstream(startedAt)
		if err != nil {
			return nil, err
		}
		s.youtubeCategoryCache.put(category.ID, items)
		s.cacheStats.warmRuns.Add(1)
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

// youtubeWarmEntry 是预热器的单个目标与调度状态。nextAt 是下次应刷新的时间;
// 初始按 youtubeWarmStartupStagger 错峰,避免进程启动瞬间 14 个目标同时打上游。
type youtubeWarmEntry struct {
	target   youtubeWarmTarget
	interval time.Duration
	nextAt   time.Time
}

// startYouTubeCacheWarmer 后台按 TTL 60% 的节奏强制刷新 6 个内置分类与
// YouTube 热词,让用户请求永远落在热缓存上(旧实现在缓存命中时直接返回,
// 预热实际上不会续期,TTL 到点后仍会出现一次 7~30s 的冷请求)。
//
// 调度规则:每次挑选"已到期且到期最早"的目标刷新;没有到期目标时睡到最近
// 到期时间(最多 youtubeWarmTick,避免空转)。失败后 youtubeWarmRetryDelay
// 再试,不因单次失败放弃该 key。只有配置了 vod_extractor 时才启动。
func (s *mediaServer) startYouTubeCacheWarmer() {
	if !s.youtubeRealtimeEnabled() {
		return
	}
	s.ensureYouTubeCaches()
	entries := make([]youtubeWarmEntry, 0, len(defaultYouTubeCategories)+len(defaultYouTubeHotKeywords))
	now := time.Now()
	for _, category := range defaultYouTubeCategories {
		entries = append(entries, youtubeWarmEntry{
			target:   youtubeWarmTarget{category: category},
			interval: youtubeCategoryWarmInterval,
		})
	}
	for _, keyword := range defaultYouTubeHotKeywords {
		entries = append(entries, youtubeWarmEntry{
			target:   youtubeWarmTarget{query: keyword},
			interval: youtubeSearchWarmInterval,
		})
	}
	if len(entries) == 0 {
		return
	}
	for index := range entries {
		entries[index].nextAt = now.Add(time.Duration(index) * youtubeWarmStartupStagger)
	}
	go func() {
		for {
			now := time.Now()
			pick := -1
			for index := range entries {
				if entries[index].nextAt.After(now) {
					continue
				}
				if pick < 0 || entries[index].nextAt.Before(entries[pick].nextAt) {
					pick = index
				}
			}
			if pick < 0 {
				// 没有到期目标:睡到最近一个到期时间,最多一个 tick。
				next := now.Add(youtubeWarmTick)
				for index := range entries {
					if entries[index].nextAt.Before(next) {
						next = entries[index].nextAt
					}
				}
				time.Sleep(next.Sub(now))
				continue
			}
			entry := &entries[pick]
			ctx, cancel := context.WithTimeout(context.Background(), youtubeWarmTimeout)
			var err error
			if entry.target.category.ID != "" {
				_, err = s.refreshYouTubeCategory(ctx, entry.target.category)
			} else {
				_, err = s.refreshYouTubeSearch(ctx, entry.target.query)
			}
			cancel()
			if err != nil {
				log.Printf("youtube cache warm %q: %v", entry.target.displayName(), err)
				entry.nextAt = time.Now().Add(youtubeWarmRetryDelay)
			} else {
				entry.nextAt = time.Now().Add(entry.interval)
			}
		}
	}()
}
