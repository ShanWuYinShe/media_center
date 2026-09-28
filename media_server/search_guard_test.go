package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// /api/search 是匿名可达的最重公开端点(count+list 两次全表 LIKE 扫描,单
// SQLite 连接串行化),本文件锁定它的两道资源防线:per-IP 限流与并发闸门。

// TestSearchRateLimited 锁定 per-IP 限流:超阈值 429;阈值按 IP 独立计数。
func TestSearchRateLimited(t *testing.T) {
	s := &mediaServer{
		searchLimiter: newIPWindowLimiter(1, time.Minute),
		searchGate:    make(chan struct{}, maxConcurrentSearches),
	}
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=x", nil)

	rec := httptest.NewRecorder()
	s.handleSearch(rec, req)
	if rec.Code != http.StatusOK { // catalog 为 nil → 空结果 200
		t.Fatalf("first request should pass, got %d body=%s", rec.Code, rec.Body.String())
	}

	rec2 := httptest.NewRecorder()
	s.handleSearch(rec2, req)
	if rec2.Code != http.StatusTooManyRequests || !strings.Contains(rec2.Body.String(), "RATE_LIMITED") {
		t.Fatalf("second request should be 429 RATE_LIMITED, got %d body=%s", rec2.Code, rec2.Body.String())
	}

	// 另一个 IP 不受当前 IP 的窗口影响。
	reqOther := httptest.NewRequest(http.MethodGet, "/api/search?q=x", nil)
	reqOther.RemoteAddr = "203.0.113.7:55555"
	rec3 := httptest.NewRecorder()
	s.handleSearch(rec3, reqOther)
	if rec3.Code != http.StatusOK {
		t.Fatalf("other IP should have its own budget, got %d", rec3.Code)
	}
}

// TestSearchGateRejectsWhenFull 锁定并发闸门:满载快速 503,不排队。
func TestSearchGateRejectsWhenFull(t *testing.T) {
	s := &mediaServer{
		searchLimiter: newIPWindowLimiter(searchPerMinuteLimit, time.Minute),
		searchGate:    make(chan struct{}, 1),
	}
	s.searchGate <- struct{}{} // 人为灌满闸门,模拟并发搜索占满

	req := httptest.NewRequest(http.MethodGet, "/api/search?q=x", nil)
	rec := httptest.NewRecorder()
	s.handleSearch(rec, req)
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "SERVICE_BUSY") {
		t.Fatalf("full gate should 503 SERVICE_BUSY, got %d body=%s", rec.Code, rec.Body.String())
	}
}

// TestSearchEmptyQueryBypassesLimiter 锁定空 query 短路在限流之前:limit=0 的
// 限流器对任何被计数的请求第二次起必拒,两个空 query 都 200 说明短路生效。
func TestSearchEmptyQueryBypassesLimiter(t *testing.T) {
	s := &mediaServer{
		searchLimiter: newIPWindowLimiter(0, time.Minute),
		searchGate:    make(chan struct{}, maxConcurrentSearches),
	}
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/api/search", nil)
		rec := httptest.NewRecorder()
		s.handleSearch(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("empty query #%d should bypass limiter, got %d", i+1, rec.Code)
		}
	}
}

// TestSearchCacheControlHeader 锁定成功响应带短缓存头,与限流预算互补。
func TestSearchCacheControlHeader(t *testing.T) {
	s := &mediaServer{
		searchLimiter: newIPWindowLimiter(searchPerMinuteLimit, time.Minute),
		searchGate:    make(chan struct{}, maxConcurrentSearches),
	}
	req := httptest.NewRequest(http.MethodGet, "/api/search?q=x", nil)
	rec := httptest.NewRecorder()
	s.handleSearch(rec, req)
	if got := rec.Header().Get("Cache-Control"); got != "public, max-age=30" {
		t.Fatalf("Cache-Control = %q, want public, max-age=30", got)
	}
}
