package main

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// resolve 结果缓存(成功 15min / 失败负缓存 5min)的单元与 handler 级行为。

func TestResolveCachePositiveLookup(t *testing.T) {
	rc := &resolveCache{entries: make(map[string]resolveCacheEntry)}
	if _, _, hit := rc.lookup("abc"); hit {
		t.Fatal("empty cache must miss")
	}
	rc.store("abc", resolveCacheEntry{
		result:    PlaybackResultResponse{DurationSeconds: 42},
		expiresAt: time.Now().Add(resolvePositiveCacheTTL),
	})
	res, reason, hit := rc.lookup("abc")
	if !hit || reason != "" || res.DurationSeconds != 42 {
		t.Fatalf("positive hit broken: hit=%v reason=%q res=%+v", hit, reason, res)
	}
}

func TestResolveCacheNegativeHit(t *testing.T) {
	rc := &resolveCache{entries: make(map[string]resolveCacheEntry)}
	rc.store("abc", resolveCacheEntry{
		failure:   true,
		reason:    "bot_check",
		expiresAt: time.Now().Add(resolveNegativeCacheTTL),
	})
	_, reason, hit := rc.lookup("abc")
	if !hit || reason != "bot_check" {
		t.Fatalf("negative hit broken: hit=%v reason=%q", hit, reason)
	}
}

func TestResolveCacheExpiryDropsEntry(t *testing.T) {
	rc := &resolveCache{entries: make(map[string]resolveCacheEntry)}
	rc.store("abc", resolveCacheEntry{
		result:    PlaybackResultResponse{},
		expiresAt: time.Now().Add(-time.Second), // 已过期
	})
	if _, _, hit := rc.lookup("abc"); hit {
		t.Fatal("expired entry must miss")
	}
	if len(rc.entries) != 0 {
		t.Fatal("expired entry must be dropped on lookup")
	}
}

func TestResolveCacheEvictsOldestAtCapacity(t *testing.T) {
	rc := &resolveCache{entries: make(map[string]resolveCacheEntry)}
	firstID := "aaaaaaaaaa0"
	for i := 0; i < maxResolveCacheEntries; i++ {
		id := strings.Repeat("a", 10) + strconv.Itoa(i)
		rc.store(id, resolveCacheEntry{
			result:    PlaybackResultResponse{},
			expiresAt: time.Now().Add(time.Hour),
		})
	}
	rc.store("newguy", resolveCacheEntry{
		result:    PlaybackResultResponse{DurationSeconds: 1},
		expiresAt: time.Now().Add(time.Hour),
	})
	if len(rc.entries) > maxResolveCacheEntries {
		t.Fatalf("cache cap exceeded: %d", len(rc.entries))
	}
	if _, _, hit := rc.lookup(firstID); hit {
		t.Fatal("oldest entry should have been evicted")
	}
	if _, _, hit := rc.lookup("newguy"); !hit {
		t.Fatal("newest entry should survive")
	}
}

// fakeExtractorUpstream 起一个 httptest 上游模拟 vod_extractor:
// mode=fail 时恒返 502+reason(每次调用计数),mode=ok 时返最小可播放 JSON。
func fakeExtractorUpstream(t *testing.T, mode string, hits *int) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*hits++
		w.Header().Set("Content-Type", "application/json")
		if mode == "fail" {
			w.WriteHeader(http.StatusBadGateway)
			_, _ = w.Write([]byte(`{"error":"extractor failed","reason":"bot_check"}`))
			return
		}
		_, _ = w.Write([]byte(`{"id":"dQw4w9WgXcQ","title":"t","duration":10,"thumbnail":"","formats":[{"format_id":"18","url":"https://v.example/x.mp4","ext":"mp4","height":360,"fps":30,"vcodec":"avc1","acodec":"mp4a","protocol":"https"}]}`))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func newResolveTestServer(t *testing.T, upstreamURL string) *mediaServer {
	t.Helper()
	return &mediaServer{
		config:                AppConfig{AppAPIToken: "tok"},
		youtubeResolveLimiter: newIPWindowLimiter(1000, time.Minute),
		yt:                    newYTClient(upstreamURL),
	}
}

func resolveRequest(t *testing.T, s *mediaServer, videoID string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/providers/youtube/resolve?id="+videoID, nil)
	req.Header.Set("X-App-Token", "tok")
	rec := httptest.NewRecorder()
	s.handleYouTubeResolve(rec, req)
	return rec
}

func swapResolveCache(t *testing.T) *resolveCache {
	t.Helper()
	old := globalResolveCache
	globalResolveCache = &resolveCache{entries: make(map[string]resolveCacheEntry)}
	t.Cleanup(func() { globalResolveCache = old })
	return globalResolveCache
}

// TestResolveHandlerNegativeCache 锁定负缓存:首次真实解析失败后,同 ID 重试
// 在 TTL 内不再打上游(bot_check 风控失败不该被反复重试消耗 cookies)。
func TestResolveHandlerNegativeCache(t *testing.T) {
	swapResolveCache(t)
	hits := 0
	srv := fakeExtractorUpstream(t, "fail", &hits)
	s := newResolveTestServer(t, srv.URL)

	rec := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("first resolve should 502, got %d", rec.Code)
	}
	rec2 := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec2.Code != http.StatusBadGateway {
		t.Fatalf("cached failure should still 502, got %d", rec2.Code)
	}
	if hits != 1 {
		t.Fatalf("upstream hit %d times, want 1 (retry must be served from negative cache)", hits)
	}
}

// TestResolveHandlerPositiveCache 锁定成功缓存:同 ID 二次解析直接回放缓存。
func TestResolveHandlerPositiveCache(t *testing.T) {
	swapResolveCache(t)
	hits := 0
	srv := fakeExtractorUpstream(t, "ok", &hits)
	s := newResolveTestServer(t, srv.URL)

	rec := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec.Code != http.StatusOK {
		t.Fatalf("first resolve should 200, got %d body=%s", rec.Code, rec.Body.String())
	}
	rec2 := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec2.Code != http.StatusOK || rec2.Body.String() != rec.Body.String() {
		t.Fatalf("cached resolve should replay identical result, got %d", rec2.Code)
	}
	if hits != 1 {
		t.Fatalf("upstream hit %d times, want 1", hits)
	}
}
