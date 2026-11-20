package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// youtube_thumb_test.go — YouTube 缩略图代理的纯逻辑与端点契约。

func TestQualityCandidates(t *testing.T) {
	if got := qualityCandidates(youtubeThumbQualityHQ); len(got) != 1 || got[0] != "hqdefault.jpg" {
		t.Fatalf("hq candidates = %v", got)
	}
	sd := qualityCandidates(youtubeThumbQualitySD)
	if len(sd) != 2 || sd[0] != "sddefault.jpg" || sd[1] != "hqdefault.jpg" {
		t.Fatalf("sd candidates = %v", sd)
	}
	mr := qualityCandidates(youtubeThumbQualityMaxres)
	if len(mr) != 3 || mr[0] != "maxresdefault.jpg" || mr[2] != "hqdefault.jpg" {
		t.Fatalf("maxres candidates = %v", mr)
	}
	if qualityCandidates("unknown") != nil {
		t.Fatalf("unknown quality must have no candidates")
	}
}

func TestIsValidYouTubeThumbQuality(t *testing.T) {
	if q, ok := isValidYouTubeThumbQuality("hq.jpg"); !ok || q != "hq" {
		t.Fatalf("hq.jpg -> %q %v", q, ok)
	}
	for _, bad := range []string{"hq", ".jpg", "uhd.jpg", "hq.png", ""} {
		if _, ok := isValidYouTubeThumbQuality(bad); ok {
			t.Fatalf("%q should be invalid", bad)
		}
	}
}

func TestYouTubeThumbURL(t *testing.T) {
	got := youtubeThumbURL("https://media.example/api/v1", "jNQXAC9IVRw", "hq")
	if got != "https://media.example/api/v1/providers/youtube/thumb/jNQXAC9IVRw/hq.jpg" {
		t.Fatalf("thumbURL = %q", got)
	}
	// 未配置公网基址:返回空串(调用方回退原始地址)。
	if got := youtubeThumbURL("", "jNQXAC9IVRw", "hq"); got != "" {
		t.Fatalf("empty base must yield empty url, got %q", got)
	}
}

func newThumbTestServer(t *testing.T, upstream *httptest.Server) *mediaServer {
	t.Helper()
	if upstream != nil {
		youtubeThumbUpstreamBase = upstream.URL + "/vi/"
	}
	t.Cleanup(func() { youtubeThumbUpstreamBase = "https://i.ytimg.com/vi/" })
	return &mediaServer{youtubeThumb: newYouTubeThumbProxy()}
}

func TestHandleYouTubeThumbFallbackAndCache(t *testing.T) {
	var maxresHits, hqHits int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/maxresdefault.jpg"):
			atomic.AddInt64(&maxresHits, 1)
			http.NotFound(w, r)
		case strings.HasSuffix(r.URL.Path, "/hqdefault.jpg"):
			atomic.AddInt64(&hqHits, 1)
			_, _ = w.Write([]byte("JPEGDATA"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	s := newThumbTestServer(t, upstream)
	// maxres 不存在 → 回退到 hq。
	rec := httptest.NewRecorder()
	s.handleYouTubeThumb(rec, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/thumb/jNQXAC9IVRw/maxres.jpg", nil), "jNQXAC9IVRw", "maxres.jpg")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if rec.Body.String() != "JPEGDATA" {
		t.Fatalf("body = %q", rec.Body.String())
	}
	if cc := rec.Header().Get("Cache-Control"); !strings.Contains(cc, "immutable") {
		t.Fatalf("Cache-Control = %q", cc)
	}
	if atomic.LoadInt64(&maxresHits) != 1 || atomic.LoadInt64(&hqHits) != 1 {
		t.Fatalf("upstream hits: maxres=%d hq=%d", maxresHits, hqHits)
	}

	// 第二次同请求:命中内存缓存,不再回源。
	rec2 := httptest.NewRecorder()
	s.handleYouTubeThumb(rec2, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/thumb/jNQXAC9IVRw/maxres.jpg", nil), "jNQXAC9IVRw", "maxres.jpg")
	if rec2.Code != http.StatusOK || rec2.Body.String() != "JPEGDATA" {
		t.Fatalf("cached response = %d %q", rec2.Code, rec2.Body.String())
	}
	if atomic.LoadInt64(&hqHits) != 1 {
		t.Fatalf("cached request must not hit upstream, hqHits=%d", hqHits)
	}
}

func TestHandleYouTubeThumbAllCandidatesMissing(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.NotFound(w, r)
	}))
	defer upstream.Close()

	s := newThumbTestServer(t, upstream)
	rec := httptest.NewRecorder()
	s.handleYouTubeThumb(rec, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/thumb/jNQXAC9IVRw/hq.jpg", nil), "jNQXAC9IVRw", "hq.jpg")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("failure must be no-store, got %q", cc)
	}
}

func TestHandleYouTubeThumbRejectsBadInput(t *testing.T) {
	s := newThumbTestServer(t, nil)
	cases := []struct{ path, id, file string }{
		{"/api/providers/youtube/thumb/bad-id/hq.jpg", "bad-id", "hq.jpg"},
		{"/api/providers/youtube/thumb/jNQXAC9IVRw/uhd.jpg", "jNQXAC9IVRw", "uhd.jpg"},
		{"/api/providers/youtube/thumb/jNQXAC9IVRw/hq", "jNQXAC9IVRw", "hq"},
		{"/api/providers/youtube/thumb/jNQXAC9IVRw/hq.png", "jNQXAC9IVRw", "hq.png"},
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		s.handleYouTubeThumb(rec, httptest.NewRequest(http.MethodGet, c.path, nil), c.id, c.file)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", c.path, rec.Code)
		}
	}
	// 视频ID大小写敏感,合法但不同 ID 是不同资源——这里只验证合法 ID 不被拒。
	rec := httptest.NewRecorder()
	s.handleYouTubeThumb(rec, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/thumb/AbC_xYz0123/hq.jpg", nil), "AbC_xYz0123", "hq.jpg")
	if rec.Code == http.StatusBadRequest {
		t.Fatalf("valid mixed-case id must not be rejected")
	}
}

func TestYouTubeSearchRewritesThumbnailsWithPublicBase(t *testing.T) {
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"_type":"playlist","entries":[
			{"_type":"url","id":"jNQXAC9IVRw","title":"Me at the zoo","channel":"jawed","duration":19.4,
			 "thumbnails":[{"url":"https://i/maxres.jpg","width":1280}]}
		]}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	s.yt.publicBase = "https://media.example/api/v1"
	rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	body := rec.Body.String()
	// 封面地址被改写为本服务代理(卡片 hq、详情 maxres)。
	for _, want := range []string{
		"providers/youtube/thumb/jNQXAC9IVRw/hq.jpg",
		"providers/youtube/thumb/jNQXAC9IVRw/maxres.jpg",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("response missing %s:\n%s", want, body)
		}
	}
	if strings.Contains(body, "i/maxres.jpg") {
		t.Errorf("original ytimg url must be replaced:\n%s", body)
	}
}
