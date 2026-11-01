package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestYouTubeCategoriesHTTP(t *testing.T) {
	s := newYouTubeTestServer(t, "http://extractor.invalid")
	rec := getJSON(t, s, "/api/v1/providers/youtube/categories", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "public, max-age=60" {
		t.Fatalf("Cache-Control = %q", got)
	}
	var body CategoriesResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Items) != len(defaultYouTubeCategories) {
		t.Fatalf("items = %d, want %d", len(body.Items), len(defaultYouTubeCategories))
	}
	for _, item := range body.Items {
		if item.ID == "" || item.Title == "" || item.Description == "" {
			t.Fatalf("invalid category: %#v", item)
		}
	}

	// 未启用实时通道时不暴露空分类,保持 200 + 空数组。
	empty := &mediaServer{}
	rec = getJSON(t, empty, "/api/v1/providers/youtube/categories", nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"items":[]`) {
		t.Fatalf("disabled: status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestYouTubeCategoryVideosHTTP(t *testing.T) {
	var gotPayload struct {
		Query string `json:"query"`
	}
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/search" {
			http.NotFound(w, r)
			return
		}
		if err := json.NewDecoder(r.Body).Decode(&gotPayload); err != nil {
			t.Errorf("decode body: %v", err)
		}
		_, _ = w.Write([]byte(`{
			"_type": "playlist",
			"entries": [
				{"_type": "url", "id": "jNQXAC9IVRw", "title": "Me at the zoo", "channel": "jawed",
				 "duration": 19.4, "thumbnail": "https://i/thumb.jpg"}
			]
		}`))
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	rec := getWithToken(t, s, "/api/providers/youtube/categories/youtube-music/videos")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	wantQuery := defaultYouTubeCategories[0].Query
	if gotPayload.Query != wantQuery {
		t.Fatalf("query = %q, want %q", gotPayload.Query, wantQuery)
	}
	for _, want := range []string{
		`"total":1`,
		`"id":"jNQXAC9IVRw"`,
		`"title":"jawed: Me at the zoo"`,
		`"pageSize":20`,
	} {
		if !strings.Contains(rec.Body.String(), want) {
			t.Errorf("response missing %s:\n%s", want, rec.Body.String())
		}
	}

	// 第 2 页:ytsearch 无翻页令牌,返回空页让客户端终止。
	rec = getWithToken(t, s, "/api/providers/youtube/categories/youtube-music/videos?page=2")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"total":0`) {
		t.Fatalf("page 2: status=%d body=%s", rec.Code, rec.Body.String())
	}

	// 缺 token / 未知分类 / 未配置 extractor。
	req := httptest.NewRequest(http.MethodGet, "/api/providers/youtube/categories/youtube-music/videos", nil)
	rec = httptest.NewRecorder()
	s.serveAPI(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("missing token: status = %d", rec.Code)
	}
	rec = getWithToken(t, s, "/api/providers/youtube/categories/nope/videos")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("unknown category: status = %d", rec.Code)
	}
	rec = getWithToken(t, newYouTubeTestServer(t, ""), "/api/providers/youtube/categories/youtube-music/videos")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("no extractor: status = %d", rec.Code)
	}
}
