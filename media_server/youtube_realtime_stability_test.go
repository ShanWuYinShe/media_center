package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// 本文件锁定 YouTube 实时通道的稳定性修复:
//  1. extractor 并发满(busy)时 media_server 短退避重试,并区分"容量繁忙(503)"
//     与"视频受限(502)";
//  2. 缓存命中不消耗 per-IP 限流配额;
//  3. 预热强制刷新(命中不短路)且 warmRuns 只在真实上游调用后计数;
//  4. 单飞 leader 被取消/panic 不牵连等待者。

func withFastBusyBackoff(t *testing.T) {
	t.Helper()
	old := extractorBusyBackoff
	extractorBusyBackoff = 5 * time.Millisecond
	t.Cleanup(func() { extractorBusyBackoff = old })
}

// TestYouTubeSearchRetriesOnExtractorBusy:第一次 503 busy、第二次 200 时,
// 搜索应重试后成功,而不是把瞬时容量问题当失败返回。
func TestYouTubeSearchRetriesOnExtractorBusy(t *testing.T) {
	withFastBusyBackoff(t)
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = io.WriteString(w, `{"error":"extractor is busy","reason":"busy"}`)
			return
		}
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"id":"jNQXAC9IVRw","title":"zoo"}]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("extractor calls = %d, want 2 (one busy + one retry)", got)
	}
}

// TestYouTubeBusyMapsTo503:重试后仍 busy 时必须返回 503 SERVICE_BUSY +
// Retry-After,不能映射成"视频受限"的 502(会误导用户以为视频不可播)。
func TestYouTubeBusyMapsTo503(t *testing.T) {
	withFastBusyBackoff(t)
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, `{"error":"extractor is busy","reason":"busy"}`)
	}))
	defer extractor.Close()

	swapResolveCache(t)
	s := newYouTubeTestServer(t, extractor.URL)

	// 搜索:503 + SERVICE_BUSY + Retry-After。
	rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "SERVICE_BUSY") {
		t.Fatalf("search busy: status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Retry-After"); got != "10" {
		t.Fatalf("Retry-After = %q, want 10", got)
	}

	// 解析:busy 不能被负缓存(否则可用窗口会被错锁成失败),第二次仍应打上游。
	resolve := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if resolve.Code != http.StatusServiceUnavailable || !strings.Contains(resolve.Body.String(), "SERVICE_BUSY") {
		t.Fatalf("resolve busy: status = %d, body = %s", resolve.Code, resolve.Body.String())
	}
	before := calls.Load()
	resolveAgain := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if resolveAgain.Code != http.StatusServiceUnavailable {
		t.Fatalf("second resolve: status = %d", resolveAgain.Code)
	}
	if calls.Load() <= before {
		t.Fatal("busy must not be negatively cached: extractor should be called again")
	}
}

// TestYouTubeCacheHitBypassesRateLimit:限流只约束真正打上游的未命中请求;
// 缓存命中(反复点同一个热词)不应被"搜索过于频繁"拦住。
func TestYouTubeCacheHitBypassesRateLimit(t *testing.T) {
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"id":"jNQXAC9IVRw","title":"zoo"}]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	// 配额只有 1 次:第一次未命中消耗掉,第二次同 query 必须靠缓存命中放行。
	s.youtubeSearchLimiter = newIPWindowLimiter(1, time.Minute)

	for i := 0; i < 2; i++ {
		rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
		if rec.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, body = %s", i, rec.Code, rec.Body.String())
		}
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("extractor calls = %d, want 1", got)
	}
	// 新的未命中 query 才消耗配额(已耗尽 → 429)。
	limited := getWithToken(t, s, "/api/providers/youtube/search?q=other")
	if limited.Code != http.StatusTooManyRequests {
		t.Fatalf("unseen query should be rate limited, status = %d", limited.Code)
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("rate limited request must not hit upstream, calls = %d", got)
	}
}

// TestYouTubeCacheHitBypassesCategoryRateLimit:分类浏览与搜索共用限流器,
// 同样只在未命中时消耗配额(旧实现连点同一分类也会 429,文案还写成"搜索")。
func TestYouTubeCacheHitBypassesCategoryRateLimit(t *testing.T) {
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"id":"jNQXAC9IVRw","title":"zoo"}]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	s.youtubeSearchLimiter = newIPWindowLimiter(1, time.Minute)

	for i := 0; i < 2; i++ {
		rec := getWithToken(t, s, "/api/providers/youtube/categories/youtube-music/videos")
		if rec.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, body = %s", i, rec.Code, rec.Body.String())
		}
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("extractor calls = %d, want 1", got)
	}
	other := getWithToken(t, s, "/api/providers/youtube/categories/youtube-tech/videos")
	if other.Code != http.StatusTooManyRequests || !strings.Contains(other.Body.String(), "分类") {
		t.Fatalf("unseen category should be rate limited with category wording, status=%d body=%s",
			other.Code, other.Body.String())
	}
}

// TestRefreshYouTubeSearchForcesUpstream:预热必须强制刷新,不能像旧实现那样
// 命中缓存就返回(否则 TTL 到点后仍会出现一次 7~30s 的冷请求);
// warmRuns 只在真实上游调用完成后 +1。
func TestRefreshYouTubeSearchForcesUpstream(t *testing.T) {
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[{"id":"jNQXAC9IVRw","title":"zoo"}]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	first := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if first.Code != http.StatusOK {
		t.Fatalf("first status = %d", first.Code)
	}
	if s.cacheStats.warmRuns.Load() != 0 {
		t.Fatal("user path must not count as warm run")
	}
	// 缓存刚写入时预热仍必须重新打上游并续期。
	if _, err := s.refreshYouTubeSearch(context.Background(), " Zoo "); err != nil {
		t.Fatalf("refresh: %v", err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("extractor calls = %d, want 2 (warm refresh must hit upstream)", got)
	}
	if got := s.cacheStats.warmRuns.Load(); got != 1 {
		t.Fatalf("warmRuns = %d, want 1", got)
	}
	// 刷新后的缓存命中不应再打上游。
	again := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if again.Code != http.StatusOK {
		t.Fatalf("cached status = %d", again.Code)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("cached hit must not call extractor, calls = %d", got)
	}
}

// TestYouTubeFlightSurvivesLeaderCancel:leader 退出(请求 ctx 取消)不能把
// context canceled 传给等待同一 key 的其它请求(旧实现会因此随机 502)。
func TestYouTubeFlightSurvivesLeaderCancel(t *testing.T) {
	group := newYouTubeFlightGroup()
	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	started := make(chan struct{})
	release := make(chan struct{})
	leaderDone := make(chan struct{})
	go func() {
		defer close(leaderDone)
		_, _ = group.do(leaderCtx, "k", func(context.Context) ([]VideoSummary, error) {
			close(started)
			<-release
			return []VideoSummary{{ID: "x"}}, nil
		})
	}()
	<-started
	cancelLeader() // leader 的调用方退出/断网

	waiterDone := make(chan struct{})
	var waiterItems []VideoSummary
	var waiterErr error
	go func() {
		defer close(waiterDone)
		waiterItems, waiterErr = group.do(context.Background(), "k", func(context.Context) ([]VideoSummary, error) {
			t.Error("waiter must not run its own upstream call")
			return nil, nil
		})
	}()
	// 给等待者足够时间进入 do() 的 join 分支再放行上游:等待者一旦 join 就阻塞在
	// flight.done 上,不会自己执行上游;这里只防"等待者还没开始就被 leader 收完"。
	time.Sleep(100 * time.Millisecond)
	close(release)
	select {
	case <-waiterDone:
	case <-time.After(2 * time.Second):
		t.Fatal("waiter did not finish")
	}
	if waiterErr != nil {
		t.Fatalf("waiter err = %v, want nil (leader cancel must not propagate)", waiterErr)
	}
	if len(waiterItems) != 1 || waiterItems[0].ID != "x" {
		t.Fatalf("waiter items = %+v, want shared result", waiterItems)
	}
	<-leaderDone
}

// TestYouTubeFlightPanicReturnsError:leader panic 必须转成 error 交给调用方
// (旧实现 return 零值 nil,nil,handler 会返回"成功但空结果"的 200)。
func TestYouTubeFlightPanicReturnsError(t *testing.T) {
	group := newYouTubeFlightGroup()
	items, err := group.do(context.Background(), "k", func(context.Context) ([]VideoSummary, error) {
		panic("boom")
	})
	if err == nil || !strings.Contains(err.Error(), "panic") {
		t.Fatalf("err = %v, want panic error", err)
	}
	if items != nil {
		t.Fatalf("items = %+v, want nil", items)
	}
	// panic 后 flight 必须清理,后续请求可正常执行。
	items, err = group.do(context.Background(), "k", func(context.Context) ([]VideoSummary, error) {
		return []VideoSummary{{ID: "ok"}}, nil
	})
	if err != nil || len(items) != 1 {
		t.Fatalf("after panic: items=%+v err=%v", items, err)
	}
}

// TestIsExtractorBusy:reason 字段(新 extractor)与 body 关键字(旧版本)都要识别。
func TestIsExtractorBusy(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{&upstreamError{status: 503, reason: "busy"}, true},
		{&upstreamError{status: 503, body: `{"error":"extractor is busy"}`}, true},
		{&upstreamError{status: 503, body: "configured cookies file missing"}, false},
		{&upstreamError{status: 502, reason: "bot_check", body: "Sign in"}, false},
		{io.EOF, false},
	}
	for _, tc := range cases {
		if got := isExtractorBusy(tc.err); got != tc.want {
			t.Errorf("isExtractorBusy(%v) = %v, want %v", tc.err, got, tc.want)
		}
	}
}
