package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// /healthz 暴露缓存命中/上游调用计数:部署后可直接确认缓存有没有在用户路径命中,
// 不必翻日志(阶段 0 度量的一部分)。
func TestHealthExposesCacheStats(t *testing.T) {
	t.Run("未发生请求时为 0 且字段齐全", func(t *testing.T) {
		s := &mediaServer{}
		var body struct {
			Cache cacheStatsSnapshot `json:"cache"`
		}
		rec := getJSON(t, s, "/api/health", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if body.Cache != (cacheStatsSnapshot{}) {
			t.Fatalf("cache = %#v, want zeros", body.Cache)
		}
	})

	t.Run("resolve 内存缓存命中计数", func(t *testing.T) {
		swapResolveCache(t)
		extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(`{"id":"jNQXAC9IVRw","title":"x","duration":19.2,"formats":[{"format_id":"18","url":"https://gvs/360","ext":"mp4","height":360,"fps":30,"vcodec":"avc1.42001E","acodec":"mp4a.40.2","protocol":"https"}]}`))
		}))
		defer extractor.Close()
		s := newYouTubeTestServer(t, extractor.URL)

		for i := 0; i < 2; i++ {
			rec := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
			if rec.Code != http.StatusOK {
				t.Fatalf("resolve %d: status = %d, body = %s", i, rec.Code, rec.Body.String())
			}
		}

		var body struct {
			Cache cacheStatsSnapshot `json:"cache"`
		}
		getJSON(t, s, "/api/health", &body)
		if body.Cache.ResolveUpstreams != 1 {
			t.Fatalf("resolveUpstreams = %d, want 1", body.Cache.ResolveUpstreams)
		}
		if body.Cache.ResolveMemoryHits != 1 {
			t.Fatalf("resolveMemoryHits = %d, want 1", body.Cache.ResolveMemoryHits)
		}
	})

	t.Run("搜索缓存命中计数", func(t *testing.T) {
		extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"_type":"url","id":"jNQXAC9IVRw","title":"zoo","channel":"jawed","duration":19.4}]}`))
		}))
		defer extractor.Close()
		s := newYouTubeTestServer(t, extractor.URL)

		for i := 0; i < 2; i++ {
			rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
			if rec.Code != http.StatusOK {
				t.Fatalf("search %d: status = %d, body = %s", i, rec.Code, rec.Body.String())
			}
		}

		var body struct {
			Cache cacheStatsSnapshot `json:"cache"`
		}
		getJSON(t, s, "/api/health", &body)
		if body.Cache.SearchHits != 1 {
			t.Fatalf("searchHits = %d, want 1", body.Cache.SearchHits)
		}
	})
}
