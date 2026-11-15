package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func testResolveResult(url string) PlaybackResultResponse {
	return PlaybackResultResponse{
		Options: []PlaybackOptionResponse{{
			ID: "q1080", Kind: "split", Label: "1080P", URL: url, QN: 1080,
		}},
		AvailableQualities: []QualityOptionResponse{{QN: 1080, Label: "1080P"}},
		DurationSeconds:    19,
	}
}

// 进程重启(关闭再打开同一文件)后仍能命中:这是把 YouTube 冷解析移出用户路径的关键。
func TestResolveDiskCachePersistsAcrossReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "resolve-cache.db")
	cache, err := openResolveDiskCache(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	result := testResolveResult("https://gvs/test")
	cache.store("jNQXAC9IVRw", result)
	if _, ok := cache.lookup("jNQXAC9IVRw"); !ok {
		t.Fatal("freshly stored entry should be present")
	}
	if err := cache.close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	reopened, err := openResolveDiskCache(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer reopened.close()
	got, ok := reopened.lookup("jNQXAC9IVRw")
	if !ok {
		t.Fatal("entry should survive reopen")
	}
	if len(got.Options) != 1 || got.Options[0].URL != result.Options[0].URL {
		t.Fatalf("result = %#v, want %#v", got, result)
	}
}

func TestResolveDiskCacheRejectsEmptyAndExpired(t *testing.T) {
	path := filepath.Join(t.TempDir(), "resolve-cache.db")
	cache, err := openResolveDiskCache(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer cache.close()

	// 无播放选项的结果不写盘:否则会变成"成功但没有地址"的 200。
	cache.store("empty", PlaybackResultResponse{})
	if _, ok := cache.lookup("empty"); ok {
		t.Fatal("empty result should not be cached")
	}

	// 手工写入过期行:读取时应判为 miss 并删除。
	payload, err := json.Marshal(testResolveResult("https://gvs/expired"))
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	_, err = cache.db.Exec(
		`INSERT INTO resolve_cache (video_id, payload, expires_at, created_at) VALUES (?, ?, ?, ?)`,
		"expired", payload, time.Now().Add(-time.Minute).Unix(), time.Now().Unix(),
	)
	if err != nil {
		t.Fatalf("insert expired: %v", err)
	}
	if _, ok := cache.lookup("expired"); ok {
		t.Fatal("expired entry should be a miss")
	}
	var remaining int
	if err := cache.db.QueryRow(`SELECT COUNT(*) FROM resolve_cache WHERE video_id = ?`, "expired").Scan(&remaining); err != nil {
		t.Fatalf("count: %v", err)
	}
	if remaining != 0 {
		t.Fatalf("expired row should be deleted, remaining = %d", remaining)
	}
}

// handler 层验证:内存缓存被清空(模拟进程重启)后,磁盘缓存仍能让第二次解析
// 不请求 vod_extractor。
func TestResolveHandlerUsesDiskCache(t *testing.T) {
	swapResolveCache(t)
	var calls atomic.Int32
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/extract" {
			http.NotFound(w, r)
			return
		}
		calls.Add(1)
		_, _ = w.Write([]byte(`{"id":"jNQXAC9IVRw","title":"x","duration":19.2,"formats":[{"format_id":"18","url":"https://gvs/360","ext":"mp4","height":360,"fps":30,"vcodec":"avc1.42001E","acodec":"mp4a.40.2","protocol":"https"}]}`))
	}))
	defer extractor.Close()

	cache, err := openResolveDiskCache(filepath.Join(t.TempDir(), "resolve-cache.db"))
	if err != nil {
		t.Fatalf("open cache: %v", err)
	}
	defer cache.close()
	s := newYouTubeTestServer(t, extractor.URL)
	s.resolveDisk = cache

	first := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if first.Code != http.StatusOK {
		t.Fatalf("first status = %d, body = %s", first.Code, first.Body.String())
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("extractor calls = %d, want 1", got)
	}

	// 模拟进程重启:内存缓存清空,磁盘缓存还在。
	globalResolveCache = &resolveCache{entries: make(map[string]resolveCacheEntry)}
	second := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if second.Code != http.StatusOK {
		t.Fatalf("second status = %d, body = %s", second.Code, second.Body.String())
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("extractor calls = %d, want 1 (disk cache should serve second request)", got)
	}
}
