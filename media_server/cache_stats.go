package main

import "sync/atomic"

// cacheStats 汇总进程内缓存命中/上游调用次数,经 /healthz 的 cache 字段暴露。
//
// 纯观测:全部 atomic 计数,不参与业务逻辑;部署后可直接验证"缓存到底有没有
// 在用户路径命中"——此前只能从日志里翻,无法快速量化(阶段 0 度量的一部分)。
type cacheStats struct {
	resolveMemoryHits atomic.Int64
	resolveDiskHits   atomic.Int64
	resolveUpstreams  atomic.Int64
	searchHits        atomic.Int64
	categoryHits      atomic.Int64
	warmRuns          atomic.Int64
}

// cacheStatsSnapshot 是 /healthz 响应里的 cache 对象(JSON 字段名稳定,便于监控采集)。
type cacheStatsSnapshot struct {
	ResolveMemoryHits int64 `json:"resolveMemoryHits"`
	ResolveDiskHits   int64 `json:"resolveDiskHits"`
	ResolveUpstreams  int64 `json:"resolveUpstreams"`
	SearchHits        int64 `json:"searchHits"`
	CategoryHits      int64 `json:"categoryHits"`
	WarmRuns          int64 `json:"warmRuns"`
}

func (c *cacheStats) snapshot() cacheStatsSnapshot {
	if c == nil {
		return cacheStatsSnapshot{}
	}
	return cacheStatsSnapshot{
		ResolveMemoryHits: c.resolveMemoryHits.Load(),
		ResolveDiskHits:   c.resolveDiskHits.Load(),
		ResolveUpstreams:  c.resolveUpstreams.Load(),
		SearchHits:        c.searchHits.Load(),
		CategoryHits:      c.categoryHits.Load(),
		WarmRuns:          c.warmRuns.Load(),
	}
}
