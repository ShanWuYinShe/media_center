package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newAppUpdateTestServer 构造一个带临时 manifest + APK 的 mediaServer,
// 用于验证更新检查返回的 apkUrl 与下载端点的缓存策略。
func newAppUpdateTestServer(t *testing.T) (*mediaServer, string, []byte, string) {
	t.Helper()
	dir := t.TempDir()
	apk := []byte("fake apk bytes for immutable download test")
	sum := sha256.Sum256(apk)
	sha := hex.EncodeToString(sum[:])
	manifest := map[string]any{
		"versionCode": 118000,
		"versionName": "1.18.0",
		"apkFilename": "media-center-app.apk",
		"sha256":      sha,
	}
	data, err := json.Marshal(manifest)
	if err != nil {
		t.Fatalf("marshal manifest: %v", err)
	}
	manifestPath := filepath.Join(dir, "app-update.json")
	if err := os.WriteFile(manifestPath, data, 0o644); err != nil {
		t.Fatalf("write manifest: %v", err)
	}
	apkPath := filepath.Join(dir, "media-center-app.apk")
	if err := os.WriteFile(apkPath, apk, 0o644); err != nil {
		t.Fatalf("write apk: %v", err)
	}
	s := &mediaServer{config: AppConfig{
		PublicBaseURL:         "https://media.example.test/api/v1",
		AppUpdateManifestPath: manifestPath,
		AppUpdateAPKPath:      apkPath,
	}}
	return s, sha[:12], apk, sha
}

// 更新检查下发的 apkUrl 必须带 v=<sha256 前 12 位>:没有版本令牌就无法把
// "URL 内容不可变"讲清楚,CDN/浏览器也就不能长缓存 APK。
func TestAppUpdateURLIncludesVersionToken(t *testing.T) {
	s, token, _, _ := newAppUpdateTestServer(t)
	req := httptest.NewRequest(http.MethodGet, "/api/app/update?versionCode=1&variant=normal", nil)
	rec := httptest.NewRecorder()
	s.handleAppUpdate(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("update status = %d, want 200", rec.Code)
	}
	var manifest AppUpdateManifest
	if err := json.Unmarshal(rec.Body.Bytes(), &manifest); err != nil {
		t.Fatalf("decode update: %v", err)
	}
	// normal 变体在服务端会归一成空 variant(sanitizeVariant),URL 里保留空参数。
	want := "https://media.example.test/api/v1/app/download?variant=&v=" + token
	if manifest.APKURL != want {
		t.Fatalf("apkUrl = %q, want %q", manifest.APKURL, want)
	}
}

// 版本化地址内容不可变 → 长缓存 + ETag;旧地址/错误令牌仍 no-store,
// 避免发版后仍把旧 APK 发给客户端。
func TestAppDownloadCachePolicy(t *testing.T) {
	s, token, apk, sha := newAppUpdateTestServer(t)
	tests := []struct {
		name          string
		query         string
		wantCacheable bool
	}{
		{"versioned url", "?variant=normal&v=" + token, true},
		{"legacy url without token", "?variant=normal", false},
		{"wrong token", "?variant=normal&v=000000000000", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/api/app/download"+tt.query, nil)
			rec := httptest.NewRecorder()
			s.handleAppDownload(rec, req)
			if rec.Code != http.StatusOK {
				t.Fatalf("download status = %d, want 200", rec.Code)
			}
			cacheControl := rec.Header().Get("Cache-Control")
			if tt.wantCacheable {
				if !strings.Contains(cacheControl, "immutable") {
					t.Fatalf("versioned download should be immutable, got Cache-Control %q", cacheControl)
				}
				expectedETag := "\"" + sha + "\""
				if got := rec.Header().Get("ETag"); got != expectedETag {
					t.Fatalf("ETag = %q, want quoted sha256", got)
				}
				if rec.Body.String() != string(apk) {
					t.Fatal("download body should be the apk bytes")
				}
				return
			}
			if cacheControl != "no-store" {
				t.Fatalf("non-versioned download must be no-store, got %q", cacheControl)
			}
		})
	}
}
