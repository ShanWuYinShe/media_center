package main

import (
	"sync/atomic"
	"time"
)

// cacheStats 汇总进程内缓存命中/上游调用次数与上游耗时,经 /healthz 的 cache 字段暴露。
//
// 纯观测:全部 atomic 计数,不参与业务逻辑;部署后可直接验证"缓存到底有没有
// 在用户路径命中",以及上游解析/搜索到底慢在哪(阶段 0 度量的一部分)。
type cacheStats struct {
	resolveMemoryHits atomic.Int64
	resolveDiskHits   atomic.Int64
	resolveUpstreams  atomic.Int64
	searchHits        atomic.Int64
	categoryHits      atomic.Int64
	warmRuns          atomic.Int64

	// 上游耗时:最近一次/累计/次数。avg 由 total/count 在快照时算出,避免读方
	// 自己除;原子写只做 Add/Store,无锁。
	resolveLastMs  atomic.Int64
	resolveTotalMs atomic.Int64
	resolveCount   atomic.Int64
	searchLastMs   atomic.Int64
}

// cacheStatsSnapshot 是 /healthz 响应里的 cache 对象(JSON 字段名稳定,便于监控采集)。
type cacheStatsSnapshot struct {
	ResolveMemoryHits int64 `json:"resolveMemoryHits"`
	ResolveDiskHits   int64 `json:"resolveDiskHits"`
	ResolveUpstreams  int64 `json:"resolveUpstreams"`
	SearchHits        int64 `json:"searchHits"`
	CategoryHits      int64 `json:"categoryHits"`
	WarmRuns          int64 `json:"warmRuns"`
	ResolveLastMs     int64 `json:"resolveLastMs"`
	ResolveAvgMs      int64 `json:"resolveAvgMs"`
	ResolveCount      int64 `json:"resolveCount"`
	SearchLastMs      int64 `json:"searchLastMs"`
}

func (c *cacheStats) snapshot() cacheStatsSnapshot {
	if c == nil {
		return cacheStatsSnapshot{}
	}
	count := c.resolveCount.Load()
	total := c.resolveTotalMs.Load()
	var avg int64
	if count > 0 {
		avg = total / count
	}
	return cacheStatsSnapshot{
		ResolveMemoryHits: c.resolveMemoryHits.Load(),
		ResolveDiskHits:   c.resolveDiskHits.Load(),
		ResolveUpstreams:  c.resolveUpstreams.Load(),
		SearchHits:        c.searchHits.Load(),
		CategoryHits:      c.categoryHits.Load(),
		WarmRuns:          c.warmRuns.Load(),
		ResolveLastMs:     c.resolveLastMs.Load(),
		ResolveAvgMs:      avg,
		ResolveCount:      count,
		SearchLastMs:      c.searchLastMs.Load(),
	}
}

// recordResolveUpstream 记录一次真实上游解析耗时(单飞 leader 调用)。
func (c *cacheStats) recordResolveUpstream(startedAt time.Time) {
	ms := time.Since(startedAt).Milliseconds()
	if ms < 0 {
		ms = 0
	}
	c.resolveLastMs.Store(ms)
	c.resolveTotalMs.Add(ms)
	c.resolveCount.Add(1)
}

// recordSearchUpstream 记录一次真实上游搜索(搜索/分类预热)耗时。
func (c *cacheStats) recordSearchUpstream(startedAt time.Time) {
	ms := time.Since(startedAt).Milliseconds()
	if ms < 0 {
		ms = 0
	}
	c.searchLastMs.Store(ms)
}
