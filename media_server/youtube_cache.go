package main

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// resolveCacheEntry 是单个视频的缓存项;failure=true 表示负缓存(命中即快速失败)。
type resolveCacheEntry struct {
	result    PlaybackResultResponse
	reason    string
	failure   bool
	expiresAt time.Time
	addedAt   time.Time
}

// resolveCache 是按 videoID 键的进程内缓存。容量有上限:extract JSON 可达
// 数 MB,但缓存的是裁剪后的 PlaybackResultResponse(KB 级),128 个上限足够
// 覆盖真实观看模式,且防止任何情况下的无界增长(容器仅 64M 内存)。
type resolveCache struct {
	mu      sync.Mutex
	entries map[string]resolveCacheEntry
}

var globalResolveCache = &resolveCache{entries: make(map[string]resolveCacheEntry)}

// lookup 命中返回 (result, reason, failure, true):failure 项 result 为零值、
// reason 为失败分类(可能为空——上游 502 未带结构化 reason 时也是失败,不能
// 把零值结果当成功回放);未命中/已过期返回 false(过期项顺手清除)。
func (rc *resolveCache) lookup(videoID string) (PlaybackResultResponse, string, bool, bool) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	e, ok := rc.entries[videoID]
	if !ok {
		return PlaybackResultResponse{}, "", false, false
	}
	if time.Now().After(e.expiresAt) {
		delete(rc.entries, videoID)
		return PlaybackResultResponse{}, "", false, false
	}
	if e.failure {
		return PlaybackResultResponse{}, e.reason, true, true
	}
	return e.result, "", false, true
}

func (rc *resolveCache) store(videoID string, entry resolveCacheEntry) {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	now := time.Now()
	entry.addedAt = now
	if len(rc.entries) >= maxResolveCacheEntries {
		// 先清过期;仍满则按加入时间淘汰最旧
		for id, e := range rc.entries {
			if now.After(e.expiresAt) {
				delete(rc.entries, id)
			}
		}
		for len(rc.entries) >= maxResolveCacheEntries {
			var oldestID string
			var oldest time.Time
			first := true
			for id, e := range rc.entries {
				if first || e.addedAt.Before(oldest) {
					oldestID, oldest, first = id, e.addedAt, false
				}
			}
			delete(rc.entries, oldestID)
		}
	}
	rc.entries[videoID] = entry
}

// ---- 同 ID 解析去重(单飞) ----
//
// App 在弱网/重试/多端同时打开同一视频时会并发请求 resolve。每次真实解析都
// 要拉起一个 yt-dlp 进程(vod_extractor 并发上限只有 2),并消耗 cookies 寿命。
// 缓存只能挡住"解析完成之后"的重复请求,挡不住"解析进行中"的并发风暴;这里
// 让同一 videoID 的在途请求共享一次上游调用,等待者复用 leader 的结果。

type resolveFlight struct {
	done   chan struct{}
	result PlaybackResultResponse
	err    error
}

type resolveGroup struct {
	mu    sync.Mutex
	calls map[string]*resolveFlight
}

var globalResolveGroup = &resolveGroup{calls: make(map[string]*resolveFlight)}

// do 执行(或加入)key 对应的解析。等待者尊重自己的 ctx 取消;leader 的 ctx
// 取消会终止上游调用,所有等待者拿到同一个错误(下一次请求会重新发起)。
func (g *resolveGroup) do(ctx context.Context, key string, fn func() (PlaybackResultResponse, error)) (PlaybackResultResponse, error) {
	g.mu.Lock()
	if flight, ok := g.calls[key]; ok {
		g.mu.Unlock()
		select {
		case <-flight.done:
			return flight.result, flight.err
		case <-ctx.Done():
			return PlaybackResultResponse{}, ctx.Err()
		}
	}
	flight := &resolveFlight{done: make(chan struct{})}
	g.calls[key] = flight
	g.mu.Unlock()

	defer func() {
		if r := recover(); r != nil {
			flight.err = fmt.Errorf("resolve panic: %v", r)
		}
		g.mu.Lock()
		delete(g.calls, key)
		g.mu.Unlock()
		close(flight.done)
	}()
	flight.result, flight.err = fn()
	return flight.result, flight.err
}
