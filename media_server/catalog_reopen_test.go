package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 启动期 catalog 打开失败后必须能后台自愈:旧实现永久降级,只能人工重启进程。
func TestCatalogReopenRecoversFromStartupFailure(t *testing.T) {
	oldDelays := catalogReopenDelays
	catalogReopenDelays = []time.Duration{5 * time.Millisecond, 10 * time.Millisecond, 20 * time.Millisecond}
	t.Cleanup(func() { catalogReopenDelays = oldDelays })

	dir := t.TempDir()
	// 用一个普通文件挡住父目录,OpenCatalog 的 MkdirAll 必然失败;删除后重开成功。
	blocker := filepath.Join(dir, "blocker")
	if err := os.WriteFile(blocker, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(blocker, "catalog.db")

	s := &mediaServer{}
	startupErr := errors.New("open catalog: mkdir: not a directory")
	s.setCatalogFailed(startupErr)
	s.startCatalogReopenLoop(path)

	// 错误态:目录端点 503,healthz degraded + 具体原因。
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers", nil))
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "CATALOG_UNAVAILABLE") {
		t.Fatalf("failed catalog should 503, got %d body=%s", rec.Code, rec.Body.String())
	}
	health := httptest.NewRecorder()
	s.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if health.Code != http.StatusServiceUnavailable {
		t.Fatalf("healthz = %d, want 503", health.Code)
	}
	if !strings.Contains(health.Body.String(), "catalog\"") ||
		!strings.Contains(health.Body.String(), "not a directory") {
		t.Fatalf("healthz should expose catalog error, got %s", health.Body.String())
	}

	// 解除阻塞,后台重开成功后应自动恢复(无需重启)。
	if err := os.Remove(blocker); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) && s.catalogHealth() != "ok" {
		time.Sleep(10 * time.Millisecond)
	}
	if s.catalogHealth() != "ok" {
		t.Fatalf("catalog did not recover, health=%s", s.catalogHealth())
	}
	rec = httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("recovered catalog should 200, got %d body=%s", rec.Code, rec.Body.String())
	}
	if c := s.catalogHandle(); c != nil {
		_ = c.Close()
	}
}

// QueryContext 必须真的把请求 ctx 传给 SQLite:取消/超时后查询可被中断，
// 不再继续占住 MaxOpenConns(1) 的唯一连接。
func TestCatalogSearchHonorsRequestContext(t *testing.T) {
	c := openTestCatalog(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err := c.SearchApprovedContext(ctx, "电影", "", 1, 10); err == nil {
		t.Fatal("canceled ctx should fail search")
	}
	if _, _, err := c.SearchApprovedContext(context.Background(), "电影", "", 1, 10); err != nil {
		t.Fatalf("background ctx should succeed: %v", err)
	}
}
