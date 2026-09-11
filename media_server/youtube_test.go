package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// ---- isValidYouTubeVideoID ----

func TestIsValidYouTubeVideoID(t *testing.T) {
	valid := []string{"dQw4w9WgXcQ", "jNQXAC9IVRw", "BaW_jenozKc", "abc-_ABC-_1"}
	for _, id := range valid {
		if !isValidYouTubeVideoID(id) {
			t.Errorf("expected %q to be valid", id)
		}
	}
	invalid := []string{"", "short", "waytoolongvideoid", "has.dot.here", "slash/attack", "../../etc", "中文中文中文中"}
	for _, id := range invalid {
		if isValidYouTubeVideoID(id) {
			t.Errorf("expected %q to be invalid", id)
		}
	}
}

// ---- heightLabel ----

func TestHeightLabel(t *testing.T) {
	cases := []struct {
		height, fps int
		want        string
	}{
		{1080, 30, "1080P"},
		{1080, 60, "1080P60"},
		{720, 50, "720P50"},
		{480, 25, "480P"},
	}
	for _, tc := range cases {
		if got := heightLabel(tc.height, tc.fps); got != tc.want {
			t.Errorf("heightLabel(%d,%d) = %q, want %q", tc.height, tc.fps, got, tc.want)
		}
	}
}

// ---- buildYouTubePlayback ----

func mkFormat(id, url, ext string, height, fps int, vcodec, acodec, protocol string) ytdlpFormat {
	return ytdlpFormat{
		FormatID: id, URL: url, Ext: ext, Height: height, FPS: fps,
		VCodec: vcodec, ACodec: acodec, Protocol: protocol,
	}
}

func TestBuildYouTubePlaybackMixedFormats(t *testing.T) {
	info := ytdlpInfo{
		ID:       "jNQXAC9IVRw",
		Title:    "Me at the zoo",
		Duration: 19.2,
		Formats: []ytdlpFormat{
			// storyboard(无视频轨)/纯音频/无高度:都应被排除
			mkFormat("sb0", "https://x/sb0", "mhtml", 720, 0, "none", "none", "https"),
			mkFormat("140", "https://x/audio", "m4a", 0, 0, "none", "mp4a.40.2", "https"),
			// progressive mp4:22=720p30、18=360p30
			mkFormat("22", "https://x/720", "mp4", 720, 30, "avc1.64001F", "mp4a.40.2", "https"),
			mkFormat("18", "https://x/360", "mp4", 360, 30, "avc1.42001E", "mp4a.40.2", "https"),
			// HLS:1080p60、1080p30(取 60)、720p60(fps 高于 progressive 720p30,保留)
			mkFormat("308", "https://x/1080p60", "mp4", 1080, 60, "avc1.64002a", "none", "m3u8_native"),
			mkFormat("303", "https://x/1080p30", "mp4", 1080, 30, "avc1.64002a", "none", "m3u8_native"),
			mkFormat("335", "https://x/720p60", "mp4", 720, 60, "avc1.64002a", "none", "m3u8_native"),
			// 同高度 fps 更高的 HLS 应取代 308
			mkFormat("999", "https://x/1080p65", "mp4", 1080, 65, "avc1.64002a", "none", "m3u8"),
		},
	}
	result := buildYouTubePlayback(info)

	if len(result.Options) != 3 {
		t.Fatalf("expected 3 options (1080/720/360), got %d: %+v", len(result.Options), result.Options)
	}
	// 高度降序
	if result.Options[0].QN != 1080 || result.Options[1].QN != 720 || result.Options[2].QN != 360 {
		t.Fatalf("expected heights [1080 720 360], got %v", []int{result.Options[0].QN, result.Options[1].QN, result.Options[2].QN})
	}
	// 1080p65 HLS 胜出(fps 最高)
	if result.Options[0].URL != "https://x/1080p65" || result.Options[0].MimeType != "application/x-mpegurl" {
		t.Fatalf("1080 option should be HLS 1080p65, got %+v", result.Options[0])
	}
	// 720p60 HLS(fps 60)保留,不被 720p30 progressive 覆盖
	if result.Options[1].URL != "https://x/720p60" {
		t.Fatalf("720 option should keep fps-60 HLS, got %+v", result.Options[1])
	}
	// 360p progressive
	if result.Options[2].URL != "https://x/360" || result.Options[2].MimeType != "video/mp4" {
		t.Fatalf("360 option should be progressive mp4, got %+v", result.Options[2])
	}
	// 清晰度列表与 options 对齐
	if len(result.AvailableQualities) != 3 || result.AvailableQualities[0].Label != "1080P65" {
		t.Fatalf("unexpected qualities: %+v", result.AvailableQualities)
	}
	// 时长取整秒
	if result.DurationSeconds != 19 {
		t.Fatalf("duration = %d, want 19", result.DurationSeconds)
	}
}

func TestBuildYouTubePlaybackProgressiveWinsOnTie(t *testing.T) {
	info := ytdlpInfo{
		ID: "abc12345678",
		Formats: []ytdlpFormat{
			mkFormat("22", "https://x/720-prog", "mp4", 720, 30, "avc1", "mp4a", "https"),
			mkFormat("335", "https://x/720-hls", "mp4", 720, 30, "avc1", "none", "m3u8_native"),
		},
	}
	result := buildYouTubePlayback(info)
	if len(result.Options) != 1 || result.Options[0].URL != "https://x/720-prog" {
		t.Fatalf("progressive should win tie at same height/fps: %+v", result.Options)
	}
}

func TestBuildYouTubePlaybackNoPlayableFormats(t *testing.T) {
	info := ytdlpInfo{
		ID:      "abc12345678",
		Formats: []ytdlpFormat{mkFormat("140", "https://x/audio", "m4a", 0, 0, "none", "mp4a", "https")},
	}
	result := buildYouTubePlayback(info)
	if len(result.Options) != 0 {
		t.Fatalf("expected no options, got %+v", result.Options)
	}
}

// ---- handler 端到端(httptest mock 上游) ----

func newYouTubeTestServer(t *testing.T, extractorURL string) *mediaServer {
	t.Helper()
	return &mediaServer{
		config:                AppConfig{AppAPIToken: "test-token"},
		yt:                    newYTClient(extractorURL),
		youtubeSearchLimiter:  newIPWindowLimiter(youtubeSearchPerMinuteLimit, time.Minute),
		youtubeResolveLimiter: newIPWindowLimiter(youtubeResolvePerMinuteLimit, time.Minute),
	}
}

func getWithToken(t *testing.T, s *mediaServer, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	req.Header.Set("X-App-Token", "test-token")
	rec := httptest.NewRecorder()
	s.serveAPI(rec, req)
	return rec
}

func TestHandleYouTubeSearch(t *testing.T) {
	var gotPayload struct {
		Query string `json:"query"`
		Limit int    `json:"limit"`
	}
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/search" {
			http.NotFound(w, r)
			return
		}
		if err := json.NewDecoder(r.Body).Decode(&gotPayload); err != nil {
			t.Errorf("decode search body: %v", err)
		}
		_, _ = w.Write([]byte(`{
			"_type": "playlist",
			"entries": [
				{"_type": "url", "id": "jNQXAC9IVRw", "title": "Me at the zoo", "channel": "jawed",
				 "duration": 19.4, "thumbnails": [
				   {"url": "https://i/default.jpg", "width": 120},
				   {"url": "https://i/maxres.jpg", "width": 1280}]},
				{"_type": "url", "id": "", "title": "no id, skipped"},
				{"_type": "url", "id": "abc12345678", "title": "live now", "duration": null,
				 "thumbnail": "https://i/fallback.jpg"}
			]
		}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if gotPayload.Query != "zoo" || gotPayload.Limit != youtubeSearchLimit {
		t.Fatalf("extractor payload = %+v", gotPayload)
	}
	body := rec.Body.String()
	for _, want := range []string{
		`"query":"zoo"`,
		`"total":2`,
		`"id":"jNQXAC9IVRw"`,
		`"title":"jawed: Me at the zoo"`,
		`"thumbnailUrl":"https://i/maxres.jpg"`,
		`"durationSeconds":19`,
		`"id":"abc12345678"`,
		`"thumbnailUrl":"https://i/fallback.jpg"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("response missing %s:\n%s", want, body)
		}
	}
	if strings.Contains(body, "nextPageToken") {
		t.Errorf("ytsearch has no pagination token, response should omit it:\n%s", body)
	}
}

func TestHandleYouTubeSearchGuards(t *testing.T) {
	// 缺 token → 401
	s := newYouTubeTestServer(t, "http://extractor.invalid")
	req := httptest.NewRequest(http.MethodGet, "/api/providers/youtube/search?q=zoo", nil)
	rec := httptest.NewRecorder()
	s.serveAPI(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("missing token: status = %d", rec.Code)
	}

	// 空 q → 空结果
	rec = getWithToken(t, s, "/api/providers/youtube/search?q=")
	if rec.Code != http.StatusOK {
		t.Fatalf("empty q: status = %d", rec.Code)
	}
	if rec.Body.String() != `{"query":"","total":0,"items":[]}` {
		t.Fatalf("empty q body = %s", rec.Body.String())
	}

	// 未配置 vod_extractor → 503
	s2 := newYouTubeTestServer(t, "")
	rec = getWithToken(t, s2, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("no extractor: status = %d", rec.Code)
	}
}

func TestHandleYouTubeSearchUpstreamError(t *testing.T) {
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(`{"error": "extractor failed"}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/search?q=zoo")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestHandleYouTubeResolve(t *testing.T) {
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/extract" {
			http.NotFound(w, r)
			return
		}
		// vod_extractor 契约:JSON body {"url": ...}(form 编码会被它 400 拒绝)。
		var payload struct {
			URL string `json:"url"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode extractor body: %v", err)
		}
		if payload.URL != "https://www.youtube.com/watch?v=jNQXAC9IVRw" {
			t.Errorf("extract url = %q", payload.URL)
		}
		_, _ = w.Write([]byte(`{
			"id": "jNQXAC9IVRw",
			"title": "Me at the zoo",
			"duration": 19.2,
			"formats": [
				{"format_id": "18", "url": "https://gvs/360", "ext": "mp4", "height": 360, "fps": 30,
				 "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "protocol": "https"},
				{"format_id": "308", "url": "https://gvs/1080", "ext": "mp4", "height": 1080, "fps": 60,
				 "vcodec": "avc1.64002a", "acodec": "none", "protocol": "m3u8_native"}
			]
		}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	for _, want := range []string{
		`"id":"yt-jNQXAC9IVRw-308"`,
		`"url":"https://gvs/1080"`,
		`"mimeType":"application/x-mpegurl"`,
		`"mimeType":"video/mp4"`,
		`"qn":1080`,
		`"qn":360`,
		`"label":"1080P60"`,
		`"durationSeconds":19`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("response missing %s:\n%s", want, body)
		}
	}
}

func TestHandleYouTubeResolveGuards(t *testing.T) {
	s := newYouTubeTestServer(t, "")

	// 未配置 extractor → 503
	rec := getWithToken(t, s, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("no extractor: status = %d", rec.Code)
	}

	// 非法 id → 400
	s3 := newYouTubeTestServer(t, "http://extractor.invalid")
	rec = getWithToken(t, s3, "/api/providers/youtube/resolve?id=../../etc")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad id: status = %d", rec.Code)
	}

	// extractor 失败 → 502
	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(`{"error": "extractor failed"}`))
	}))
	defer failing.Close()
	s4 := newYouTubeTestServer(t, failing.URL)
	rec = getWithToken(t, s4, "/api/providers/youtube/resolve?id=jNQXAC9IVRw")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("extractor fail: status = %d", rec.Code)
	}
}

func TestYouTubeRoutesAreProviderScoped(t *testing.T) {
	// 非 youtube provider 不应命中 search/resolve。
	s := newYouTubeTestServer(t, "")
	req := httptest.NewRequest(http.MethodGet, "/api/providers/bilibili/search?q=zoo", nil)
	req.Header.Set("X-App-Token", "test-token")
	rec := httptest.NewRecorder()
	s.serveAPI(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("bilibili search should 404, got %d", rec.Code)
	}
}
