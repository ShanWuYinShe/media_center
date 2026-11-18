package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestClassifyYouTubeFailure(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want youtubeFailureClass
	}{
		{"content unavailable", &upstreamError{status: 502, reason: "unavailable"}, youtubeFailureContent},
		{"content no_formats", &upstreamError{status: 502, reason: "no_formats"}, youtubeFailureContent},
		{"content geo_blocked", &upstreamError{status: 502, reason: "geo_blocked"}, youtubeFailureContent},
		{"content private", &upstreamError{status: 502, reason: "private"}, youtubeFailureContent},
		{"legacy 502 without reason stays content", &upstreamError{status: 502}, youtubeFailureContent},
		{"infra bot_check", &upstreamError{status: 502, reason: "bot_check"}, youtubeFailureInfra},
		{"infra rate_limited", &upstreamError{status: 502, reason: "rate_limited"}, youtubeFailureInfra},
		{"infra timeout", &upstreamError{status: 504, reason: "timeout"}, youtubeFailureInfra},
		{"infra cookies config", &upstreamError{status: 503, reason: "cookies"}, youtubeFailureInfra},
		{"transport error", errors.New("extractor request: connection refused"), youtubeFailureInfra},
	}
	for _, tt := range tests {
		if got := classifyYouTubeFailure(tt.err); got != tt.want {
			t.Errorf("%s: classify = %v, want %v", tt.name, got, tt.want)
		}
	}
}

func TestYouTubeCircuitBreakerStateMachine(t *testing.T) {
	oldThreshold, oldCooldown := youtubeCircuitThreshold, youtubeCircuitCooldown
	youtubeCircuitThreshold, youtubeCircuitCooldown = 3, 50*time.Millisecond
	t.Cleanup(func() {
		youtubeCircuitThreshold, youtubeCircuitCooldown = oldThreshold, oldCooldown
	})

	b := newYouTubeCircuitBreaker()
	now := time.Now()
	if ok, _ := b.allow(now); !ok {
		t.Fatal("closed circuit should allow")
	}
	for i := 0; i < 3; i++ {
		b.recordInfraFailure("bot_check", now)
	}
	if ok, retry := b.allow(now); ok || retry <= 0 {
		t.Fatalf("opened circuit should block: ok=%v retry=%v", ok, retry)
	}
	if snap := b.snapshot(now); snap["circuit"] != "open" {
		t.Fatalf("snapshot circuit = %v, want open", snap["circuit"])
	}

	// 冷却到期 → 半开放行一个探测请求。
	if ok, _ := b.allow(now.Add(youtubeCircuitCooldown + time.Millisecond)); !ok {
		t.Fatal("half-open circuit should allow one probe")
	}
	// 探测成功 → 关闭并清零。
	b.recordSuccess()
	if snap := b.snapshot(now.Add(time.Second)); snap["circuit"] != "closed" {
		t.Fatalf("snapshot after success = %v, want closed", snap["circuit"])
	}
	if snap := b.snapshot(now.Add(time.Second)); snap["consecutiveFailures"] != 0 {
		t.Fatalf("consecutiveFailures = %v, want 0", snap["consecutiveFailures"])
	}
}

// extractorWithReason 返回固定状态码/reason 的假 extractor;计数用于断言是否真的打了上游。
func extractorWithReason(t *testing.T, status int, reason string, hits *int) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*hits++
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"error":"upstream failed","reason":"` + reason + `"}`))
	}))
	t.Cleanup(srv.Close)
	return srv
}

// 基础设施类失败(cookies 过期/风控)必须映射成 503 而不是 502「视频受限」,
// 且不按视频负缓存:第二次请求仍应真正打上游(由熔断而非视频级缓存兜底)。
func TestYouTubeResolveInfraFailureMaps503AndSkipsNegativeCache(t *testing.T) {
	swapResolveCache(t)
	hits := 0
	srv := extractorWithReason(t, http.StatusBadGateway, "bot_check", &hits)
	s := newResolveTestServer(t, srv.URL)

	rec := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "RESOLVE_UNAVAILABLE") {
		t.Fatalf("infra failure should 503 RESOLVE_UNAVAILABLE, got %d body=%s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Retry-After"); got == "" {
		t.Fatal("infra failure should carry Retry-After")
	}
	rec2 := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec2.Code != http.StatusServiceUnavailable {
		t.Fatalf("second infra failure = %d, want 503", rec2.Code)
	}
	if hits != 2 {
		t.Fatalf("upstream hits = %d, want 2 (infra failures must not be video-level negative cached)", hits)
	}
}

func TestYouTubeSearchInfraFailureMaps503(t *testing.T) {
	hits := 0
	srv := extractorWithReason(t, http.StatusBadGateway, "rate_limited", &hits)
	s := newYouTubeTestServer(t, srv.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "UPSTREAM_UNAVAILABLE") {
		t.Fatalf("search infra failure should 503 UPSTREAM_UNAVAILABLE, got %d body=%s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Retry-After"); got == "" {
		t.Fatal("search infra failure should carry Retry-After")
	}
}

// 缓存命中不应消耗 per-IP 配额(与搜索/分类口径一致)。
func TestYouTubeResolveCacheHitBypassesRateLimit(t *testing.T) {
	swapResolveCache(t)
	hits := 0
	srv := fakeExtractorUpstream(t, "ok", &hits)
	s := &mediaServer{
		config:                AppConfig{AppAPIToken: "tok"},
		yt:                    newYTClient(srv.URL),
		youtubeResolveLimiter: newIPWindowLimiter(1, time.Minute),
	}
	if rec := resolveRequest(t, s, "dQw4w9WgXcQ"); rec.Code != http.StatusOK {
		t.Fatalf("first resolve = %d, want 200", rec.Code)
	}
	if rec := resolveRequest(t, s, "dQw4w9WgXcQ"); rec.Code != http.StatusOK {
		t.Fatalf("cache hit must bypass rate limit, got %d body=%s", rec.Code, rec.Body.String())
	}
	if rec := resolveRequest(t, s, "jNQXAC9IVRw"); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("cache miss beyond quota = %d, want 429", rec.Code)
	}
}

// 分类端点必须在 App 15s 读超时之前返回结论:超时映射 503 CONTENT_PREPARING,
// 后台单飞会继续跑完并填充缓存。
func TestYouTubeCategoryTimeoutMapsContentPreparing(t *testing.T) {
	old := youtubeCategoryTimeout
	youtubeCategoryTimeout = 100 * time.Millisecond
	t.Cleanup(func() { youtubeCategoryTimeout = old })

	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(500 * time.Millisecond)
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[]}`))
	}))
	t.Cleanup(extractor.Close)
	s := newYouTubeTestServer(t, extractor.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/categories/youtube-music/videos")
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "CONTENT_PREPARING") {
		t.Fatalf("category timeout should 503 CONTENT_PREPARING, got %d body=%s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Retry-After"); got != "5" {
		t.Fatalf("Retry-After = %q, want 5", got)
	}
}

// 连续基础设施失败打开熔断后,后续请求快失败且不再打上游;/healthz 反映熔断状态。
func TestYouTubeResolveCircuitBreakerOpens(t *testing.T) {
	swapResolveCache(t)
	oldThreshold, oldCooldown := youtubeCircuitThreshold, youtubeCircuitCooldown
	youtubeCircuitThreshold, youtubeCircuitCooldown = 2, 2*time.Second
	t.Cleanup(func() {
		youtubeCircuitThreshold, youtubeCircuitCooldown = oldThreshold, oldCooldown
	})

	hits := 0
	srv := extractorWithReason(t, http.StatusBadGateway, "bot_check", &hits)
	s := newResolveTestServer(t, srv.URL)

	ids := []string{"AAAAAAAAAAA", "BBBBBBBBBBB", "CCCCCCCCCCC"}
	for _, id := range ids[:2] {
		if rec := resolveRequest(t, s, id); rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("resolve %s = %d, want 503", id, rec.Code)
		}
	}
	if hits != 2 {
		t.Fatalf("upstream hits = %d, want 2", hits)
	}
	// 熔断已打开:第 3 个视频应快失败,不再打上游。
	if rec := resolveRequest(t, s, ids[2]); rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "RESOLVE_UNAVAILABLE") {
		t.Fatalf("open circuit should 503 RESOLVE_UNAVAILABLE, got %d body=%s", rec.Code, rec.Body.String())
	}
	if hits != 2 {
		t.Fatalf("open circuit should not hit upstream, hits = %d", hits)
	}

	rec := getWithToken(t, s, "/healthz")
	var body struct {
		YouTube map[string]any `json:"youtube"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode healthz: %v body=%s", err, rec.Body.String())
	}
	if body.YouTube == nil || body.YouTube["circuit"] != "open" {
		t.Fatalf("healthz youtube = %#v, want circuit=open", body.YouTube)
	}
}
