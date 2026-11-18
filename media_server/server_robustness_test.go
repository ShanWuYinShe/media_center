package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// catalog 配置了但打开失败时,公开目录端点必须 503 而不是 200 空目录:
// 空目录会被客户端当成功缓存,故障恢复后仍长时间看不到内容。
func TestCatalogUnavailableReturns503(t *testing.T) {
	s := &mediaServer{catalogErr: os.ErrPermission}
	tests := []string{
		"/api/v1/providers",
		"/api/search?q=%E7%94%B5%E5%BD%B1",
		"/api/v1/providers/bilibili/bootstrap",
		"/api/v1/providers/bilibili/categories",
		"/api/v1/providers/bilibili/categories/movies/videos",
		"/api/v1/providers/bilibili/videos/BV1xxxx/collection",
	}
	for _, path := range tests {
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "CATALOG_UNAVAILABLE") {
			t.Fatalf("%s: status=%d body=%s, want 503 CATALOG_UNAVAILABLE", path, rec.Code, rec.Body.String())
		}
		if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
			t.Fatalf("%s: Cache-Control = %q, want no-store", path, cc)
		}
	}
}

// bilibili 目录故障不应连带 YouTube 实时分区:bootstrap 仍应 200。
func TestCatalogUnavailableDoesNotBlockYouTube(t *testing.T) {
	s := &mediaServer{
		catalogErr: os.ErrPermission,
		yt:         newYTClient("http://extractor.invalid"),
	}
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers/youtube/bootstrap", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("youtube bootstrap should not be blocked by catalog failure, status=%d body=%s", rec.Code, rec.Body.String())
	}
}

// 更新清单损坏/APK 缺失必须返回 503 UPDATE_UNAVAILABLE,并在 /healthz 暴露
// appUpdate=error(旧实现静默 204,更新通道失效且无信号)。
func TestAppUpdateBrokenManifestReturns503(t *testing.T) {
	dir := t.TempDir()
	manifestPath := filepath.Join(dir, "app-update.json")
	// sha256 非法:validateAppUpdateManifest 会拒绝。
	if err := os.WriteFile(manifestPath, []byte(`{"versionCode":2,"versionName":"1.1.0","sha256":"short"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := &mediaServer{config: AppConfig{
		AppUpdateManifestPath: manifestPath,
		AppUpdateAPKPath:      filepath.Join(dir, "media-center-app.apk"),
	}}
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/app/update?versionCode=0", nil))
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "UPDATE_UNAVAILABLE") {
		t.Fatalf("broken manifest: status=%d body=%s, want 503 UPDATE_UNAVAILABLE", rec.Code, rec.Body.String())
	}

	health := httptest.NewRecorder()
	s.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	var payload map[string]any
	if err := json.Unmarshal(health.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode healthz: %v", err)
	}
	if got := payload["appUpdate"]; got != "error" {
		t.Fatalf("healthz appUpdate = %v, want error", got)
	}
}

func TestAppUpdateMissingManifestStill204(t *testing.T) {
	s := &mediaServer{config: AppConfig{
		AppUpdateManifestPath: filepath.Join(t.TempDir(), "missing.json"),
	}}
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/app/update?versionCode=0", nil))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("missing manifest should 204, got %d", rec.Code)
	}
	health := httptest.NewRecorder()
	s.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	var payload map[string]any
	if err := json.Unmarshal(health.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode healthz: %v", err)
	}
	if got := payload["appUpdate"]; got != "missing" {
		t.Fatalf("healthz appUpdate = %v, want missing", got)
	}
}

// 旧版本令牌的下载地址内容已不存在:必须 410 让客户端重新检查更新,
// 而不是 200 返回当前 APK(客户端 sha256 校验失败后白下载整包)。
func TestAppDownloadVersionMismatchReturns410(t *testing.T) {
	s, _, _, _ := newAppUpdateTestServer(t)
	rec := httptest.NewRecorder()
	s.handleAppDownload(rec, httptest.NewRequest(http.MethodGet, "/api/app/download?variant=normal&v=000000000000", nil))
	if rec.Code != http.StatusGone || !strings.Contains(rec.Body.String(), "APK_VERSION_GONE") {
		t.Fatalf("stale version token: status=%d body=%s, want 410 APK_VERSION_GONE", rec.Code, rec.Body.String())
	}
	if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", cc)
	}
}

// 合集队列(EXISTS 子查询)依赖 (provider,native_id) 索引;存量库打开时自动补建。
func TestCatalogHasProviderNativeIndex(t *testing.T) {
	c := openTestCatalog(t)
	var name string
	err := c.db.QueryRow(`SELECT name FROM sqlite_master WHERE type='index' AND name='idx_members_provider_native'`).Scan(&name)
	if err != nil {
		t.Fatalf("query index: %v", err)
	}
	if name != "idx_members_provider_native" {
		t.Fatalf("index name = %q", name)
	}
}

// 超大合集全量下发会让响应体随集数线性增长;队列上限 500 集,Total 仍为真实总数。
func TestCollectionQueueCapped(t *testing.T) {
	c := openTestCatalog(t)
	const episodes = 520
	ids := make([]string, 0, episodes)
	for i := 0; i < episodes; i++ {
		id := fmt.Sprintf("BVcap%04d", i)
		ids = append(ids, id)
		mustUpsert(t, c, "bilibili", id, id, 600)
		approveTo(t, c, "movies", "bilibili", id)
	}
	if _, _, err := c.ReplaceCollections("bilibili", []CollectionImport{{ID: "big-season", Title: "大合集", VideoIDs: ids}}); err != nil {
		t.Fatalf("replace collections: %v", err)
	}
	s := &mediaServer{catalog: c}
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers/bilibili/videos/BVcap0000/collection", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("collection status = %d body=%s", rec.Code, rec.Body.String())
	}
	var resp VideoCollectionResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode collection: %v", err)
	}
	if resp.Collection == nil || resp.Collection.Total != episodes {
		t.Fatalf("collection total = %+v, want %d", resp.Collection, episodes)
	}
	if len(resp.Items) != maxCollectionQueueItems {
		t.Fatalf("items = %d, want capped %d", len(resp.Items), maxCollectionQueueItems)
	}
}
