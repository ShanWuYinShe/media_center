package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// TestProviderCredentialsTokenGuard 验证凭证端点不再匿名可访问:
// 未配置 token 时一律 401;配置后要求 X-App-Token 匹配,错 token 401、对 token 200。
func TestProviderCredentialsTokenGuard(t *testing.T) {
	s := &mediaServer{
		config:             AppConfig{AppAPIToken: "sekret-token"},
		credentials:        []ProviderCredential{{Provider: "bilibili", Fields: map[string]string{"SESSDATA": "x"}}},
		credentialsLimiter: newIPWindowLimiter(credentialsPerMinuteLimit, time.Minute),
	}

	t.Run("no token header rejected", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/api/providers/credentials", nil)
		rec := httptest.NewRecorder()
		s.handleProviderCredentials(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401 (body=%s)", rec.Code, rec.Body.String())
		}
	})

	t.Run("wrong token rejected", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/api/providers/credentials", nil)
		req.Header.Set("X-App-Token", "wrong")
		rec := httptest.NewRecorder()
		s.handleProviderCredentials(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401", rec.Code)
		}
	})

	t.Run("correct token accepted", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/api/providers/credentials", nil)
		req.Header.Set("X-App-Token", "sekret-token")
		rec := httptest.NewRecorder()
		s.handleProviderCredentials(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
		}
		if !strings.Contains(rec.Body.String(), `"provider":"bilibili"`) {
			t.Errorf("response missing credentials: %s", rec.Body.String())
		}
	})

	t.Run("disabled when no token configured", func(t *testing.T) {
		noToken := &mediaServer{
			config:             AppConfig{},
			credentialsLimiter: newIPWindowLimiter(credentialsPerMinuteLimit, time.Minute),
		}
		req := httptest.NewRequest(http.MethodGet, "/api/providers/credentials", nil)
		req.Header.Set("X-App-Token", "anything")
		rec := httptest.NewRecorder()
		noToken.handleProviderCredentials(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401 when no token configured", rec.Code)
		}
	})
}

// TestProviderCredentialsRateLimit 验证凭证端点 per-IP 限流生效。
func TestProviderCredentialsRateLimit(t *testing.T) {
	s := &mediaServer{
		config:             AppConfig{AppAPIToken: "t"},
		credentialsLimiter: newIPWindowLimiter(2, time.Minute),
	}
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/api/providers/credentials", nil)
		req.Header.Set("X-App-Token", "t")
		req.RemoteAddr = "203.0.113.9:1234"
		rec := httptest.NewRecorder()
		s.handleProviderCredentials(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, want 200", i+1, rec.Code)
		}
	}
	req := httptest.NewRequest(http.MethodGet, "/api/providers/credentials", nil)
	req.Header.Set("X-App-Token", "t")
	req.RemoteAddr = "203.0.113.9:1234"
	rec := httptest.NewRecorder()
	s.handleProviderCredentials(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429 after limit", rec.Code)
	}
}

// TestIsAllowedDownloadHost 验证 App 更新下载 Host 校验:
// 显式白名单、loopback、私有网段放行;公网未知域名拒绝(Host 头投毒防护)。
func TestIsAllowedDownloadHost(t *testing.T) {
	s := &mediaServer{config: AppConfig{AllowedPublicHosts: []string{"yinghuoren.com"}}}

	allowed := []string{
		"yinghuoren.com",
		"yinghuoren.com:443",
		"127.0.0.1",
		"localhost",
		"[::1]",
		"10.1.2.3",
		"172.20.0.5",
		"192.168.1.100",
	}
	for _, host := range allowed {
		if !s.isAllowedDownloadHost(host) {
			t.Errorf("host %q should be allowed", host)
		}
	}

	rejected := []string{
		"",
		"evil.example.com",
		"attacker.com:8443",
		"1.2.3.4", // 公网 IP 不在白名单
	}
	for _, host := range rejected {
		if s.isAllowedDownloadHost(host) {
			t.Errorf("host %q should be rejected", host)
		}
	}

	// 未配置白名单时:仅本地开发网段放行,公网一律拒绝。
	s2 := &mediaServer{config: AppConfig{}}
	if s2.isAllowedDownloadHost("evil.example.com") {
		t.Error("no whitelist: public host should be rejected")
	}
	if !s2.isAllowedDownloadHost("localhost") {
		t.Error("no whitelist: localhost should be allowed for dev")
	}
}
