package main

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 错误响应必须禁止缓存:成功路径设置的 public/max-age 不能泄漏到 4xx/5xx,
// 否则一次参数错误或瞬时 DB 故障会在缓存 TTL 内被持续回放(表现为"分类打不开/搜索一直失败")。
func TestErrorResponsesAreNotCacheable(t *testing.T) {
	s := &mediaServer{}
	cases := []struct {
		name string
		path string
		want int
	}{
		{"分页参数非法", "/api/v1/providers/bilibili/categories/movies/videos?page=abc", http.StatusBadRequest},
		{"未知分类", "/api/v1/providers/bilibili/categories/unknown-cat/videos", http.StatusNotFound},
		{"搜索关键词过长", "/api/v1/search?q=" + strings.Repeat("a", maxSearchQueryBytes+1), http.StatusBadRequest},
		{"凭证未授权", "/api/v1/providers/credentials", http.StatusUnauthorized},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, tc.path, nil))
			if rec.Code != tc.want {
				t.Fatalf("status = %d, want %d (body: %s)", rec.Code, tc.want, rec.Body.String())
			}
			if got := rec.Header().Get("Cache-Control"); got != "no-store" {
				t.Fatalf("Cache-Control = %q, want no-store", got)
			}
		})
	}
}

// 凭证成功响应同样不得缓存:共享账号会话 cookie 轮换后旧值不能被中间缓存回放。
func TestCredentialsSuccessIsNotCacheable(t *testing.T) {
	s := &mediaServer{
		config:             AppConfig{AppAPIToken: "test-token"},
		credentialsLimiter: newIPWindowLimiter(credentialsPerMinuteLimit, time.Minute),
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/providers/credentials", nil)
	req.Header.Set("X-App-Token", "test-token")
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", got)
	}
}

// App 更新检查的 204(已是最新/清单缺失)是默认可缓存状态码,不显式 no-store
// 会让发布新版本后客户端长期拿到旧 204,更新通道静默失效。
func TestAppUpdateNoContentIsNotCacheable(t *testing.T) {
	s := &mediaServer{config: AppConfig{
		AppUpdateManifestPath: filepath.Join(t.TempDir(), "missing-app-update.json"),
	}}
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/app/update?versionCode=1", nil))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204 (body: %s)", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", got)
	}
}

// 反向断言:上述修复不能把成功响应的 public 缓存一起关掉,否则目录接口的
// 客户端/代理缓存收益会丢失。
func TestProvidersSuccessKeepsPublicCache(t *testing.T) {
	rec := httptest.NewRecorder()
	(&mediaServer{}).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if got := rec.Header().Get("Cache-Control"); got != "public, max-age=60" {
		t.Fatalf("Cache-Control = %q, want public, max-age=60", got)
	}
}
