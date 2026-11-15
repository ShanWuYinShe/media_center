package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// 分类页与搜索在 TTL 内必须直接命中缓存:这是把 YouTube 实时通道从"秒级"变
// "毫秒级"的关键,也避免分类浏览占满 vod_extractor 的两个解析槽。
func TestYouTubeSearchResultsUseCache(t *testing.T) {
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"_type":"url","id":"jNQXAC9IVRw","title":"Me at the zoo","channel":"jawed","duration":19.4,"thumbnail":"https://i/t.jpg"}]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	first := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if first.Code != http.StatusOK {
		t.Fatalf("first status = %d, body = %s", first.Code, first.Body.String())
	}
	// 大小写/首尾空格归一化后同 key:第二次不再打上游。
	second := getWithToken(t, s, "/api/providers/youtube/search?q=%20Zoo%20")
	if second.Code != http.StatusOK {
		t.Fatalf("second status = %d, body = %s", second.Code, second.Body.String())
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("extractor calls = %d, want 1 (second search should hit cache)", got)
	}
	if !strings.Contains(second.Body.String(), `"id":"jNQXAC9IVRw"`) {
		t.Fatalf("second response missing cached item: %s", second.Body.String())
	}
}

func TestYouTubeCategoryVideosUseCache(t *testing.T) {
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"_type":"url","id":"jNQXAC9IVRw","title":"Me at the zoo","channel":"jawed","duration":19.4,"thumbnail":"https://i/t.jpg"}]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	first := getWithToken(t, s, "/api/providers/youtube/categories/youtube-music/videos")
	if first.Code != http.StatusOK {
		t.Fatalf("first status = %d, body = %s", first.Code, first.Body.String())
	}
	second := getWithToken(t, s, "/api/providers/youtube/categories/youtube-music/videos")
	if second.Code != http.StatusOK {
		t.Fatalf("second status = %d, body = %s", second.Code, second.Body.String())
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("extractor calls = %d, want 1 (second category request should hit cache)", got)
	}
	if !strings.Contains(second.Body.String(), `"total":1`) {
		t.Fatalf("second response missing cached items: %s", second.Body.String())
	}
}

func TestYouTubeResultCacheExpiresAndEvicts(t *testing.T) {
	cache := newYouTubeResultCache(2, 20*time.Millisecond)
	cache.put("a", []VideoSummary{{ID: "a"}})
	if _, ok := cache.get("a"); !ok {
		t.Fatal("fresh entry should be present")
	}
	time.Sleep(30 * time.Millisecond)
	if _, ok := cache.get("a"); ok {
		t.Fatal("expired entry should be evicted on read")
	}

	cache.put("b", []VideoSummary{{ID: "b"}})
	cache.put("c", []VideoSummary{{ID: "c"}})
	cache.put("d", []VideoSummary{{ID: "d"}})
	if got := cache.size(); got > 2 {
		t.Fatalf("cache size = %d, want <= 2", got)
	}
}
