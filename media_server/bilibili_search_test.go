package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// bilibili_search_test.go — B 站全站搜索的纯逻辑与端点契约。

func TestWbiKeyFromURL(t *testing.T) {
	cases := map[string]string{
		"https://i0.hdslb.com/bfs/wbi/941338484aae1ad9425b84077c.png": "941338484aae1ad9425b84077c",
		"941338484aae1ad9425b84077c.png":                              "941338484aae1ad9425b84077c",
		"https://i0.hdslb.com/bfs/wbi/abc.png?x=1":                    "abc",
		"": "",
	}
	for input, want := range cases {
		if got := wbiKeyFromURL(input); got != want {
			t.Fatalf("wbiKeyFromURL(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestWbiMixinKeyDeterministic(t *testing.T) {
	// 置换表固定:同一输入必须得到同一 mixin key;且长度恰为 32。
	img := "941338484aae1ad9425b84077c"
	sub := "ff0ff746eab6f01bf08b70ac45"
	key := wbiMixinKey(img, sub)
	if len(key) != 32 {
		t.Fatalf("mixin key length = %d, want 32", len(key))
	}
	if key != wbiMixinKey(img, sub) {
		t.Fatalf("mixin key must be deterministic")
	}
	// 首字符来自置换表索引 46 → origin[46]。
	origin := img + sub
	if key[0] != origin[46] {
		t.Fatalf("mixin key first char mismatch: got %q want %q", key[0], origin[46])
	}
}

func TestSignWbiAddsTimestampAndSignature(t *testing.T) {
	signed := signWbi(map[string]string{"keyword": "黑神话", "page": "1"}, "0123456789abcdef0123456789abcdef")
	if signed["wts"] == "" {
		t.Fatalf("wts must be set")
	}
	if signed["w_rid"] == "" || len(signed["w_rid"]) != 32 {
		t.Fatalf("w_rid must be 32-char md5 hex, got %q", signed["w_rid"])
	}
	// 原参数必须保留。
	if signed["keyword"] != "黑神话" || signed["page"] != "1" {
		t.Fatalf("original params must be preserved")
	}
}

func TestParseBilibiliDuration(t *testing.T) {
	cases := map[string]int64{
		"15:53":   953,
		"1358:52": 81532,
		"1:02:03": 3723,
		"0:00":    0,
		"":        0,
		"abc":     0,
		"12:xx":   0,
		"12:-3":   0,
	}
	for input, want := range cases {
		if got := parseBilibiliDuration(input); got != want {
			t.Fatalf("parseBilibiliDuration(%q) = %d, want %d", input, got, want)
		}
	}
}

func TestCleanBilibiliSearchTitle(t *testing.T) {
	cases := []struct {
		title, author, want string
	}{
		{"<em class=\"keyword\">黑神话</em>实机", "", "黑神话实机"},
		{"普通标题", "UP主", "UP主: 普通标题"},
		{"A&amp;B", "up", "up: A&B"},
		{"  空格  ", "", "空格"},
	}
	for _, c := range cases {
		if got := cleanBilibiliSearchTitle(c.title, c.author); got != c.want {
			t.Fatalf("cleanBilibiliSearchTitle(%q,%q) = %q, want %q", c.title, c.author, got, c.want)
		}
	}
}

func TestUpgradeToHTTPS(t *testing.T) {
	cases := map[string]string{
		"//i0.hdslb.com/x.jpg":  "https://i0.hdslb.com/x.jpg",
		"http://i0.hdslb.com/x": "https://i0.hdslb.com/x",
		"https://a.b/c":         "https://a.b/c",
		"":                      "",
	}
	for input, want := range cases {
		if got := upgradeToHTTPS(input); got != want {
			t.Fatalf("upgradeToHTTPS(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestBilibiliSearchVideosParsesUpstream(t *testing.T) {
	// 用假上游验证:响应解析、bvid 过滤、标题清洗、总数回落。
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/x/web-interface/nav":
			_, _ = w.Write([]byte(`{"code":-101,"data":{"wbi_img":{"img_url":"https://x/aaabbb.png","sub_url":"https://x/cccddd.png"}}}`))
		case "/x/web-interface/wbi/search/type":
			if r.URL.Query().Get("w_rid") == "" || r.URL.Query().Get("wts") == "" {
				t.Errorf("search request must carry WBI signature, got %v", r.URL.RawQuery)
			}
			if r.Header.Get("Referer") != bilibiliReferer {
				t.Errorf("search request must carry Referer")
			}
			_, _ = w.Write([]byte(`{"code":0,"data":{"numResults":1000,"result":[
				{"bvid":"BV1kS8H6VERt","title":"<em class=\"keyword\">黑神话</em>演示","author":"游戏科学","duration":"15:53","pic":"//i0.hdslb.com/a.jpg"},
				{"bvid":"","title":"无 bvid 应被过滤"},
				{"bvid":"BV11PcgzWEJp","title":"第二条","author":"某人","duration":"6:47","pic":"http://i0.hdslb.com/b.jpg"}
			]}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client := newBilibiliSearchClient()
	// 覆盖上游基址指向假服务器。
	origNav, origSearch := bilibiliNavURL, bilibiliSearchURL
	bilibiliNavURL = server.URL + "/x/web-interface/nav"
	bilibiliSearchURL = server.URL + "/x/web-interface/wbi/search/type"
	defer func() { bilibiliNavURL, bilibiliSearchURL = origNav, origSearch }()

	items, total, err := client.searchVideos(context.Background(), "黑神话", 1)
	if err != nil {
		t.Fatalf("searchVideos: %v", err)
	}
	if total != 1000 {
		t.Fatalf("total = %d, want 1000", total)
	}
	if len(items) != 2 {
		t.Fatalf("items = %d, want 2 (empty bvid filtered)", len(items))
	}
	if items[0].ID != "BV1kS8H6VERt" || items[0].Title != "游戏科学: 黑神话演示" {
		t.Fatalf("item0 = %+v", items[0])
	}
	if items[0].DurationSeconds != 953 {
		t.Fatalf("duration = %d, want 953", items[0].DurationSeconds)
	}
	if items[0].ThumbnailURL != "https://i0.hdslb.com/a.jpg" {
		t.Fatalf("thumb = %q", items[0].ThumbnailURL)
	}
	if items[1].ThumbnailURL != "https://i0.hdslb.com/b.jpg" {
		t.Fatalf("thumb1 = %q", items[1].ThumbnailURL)
	}

	// 缓存命中:再次搜索不打上游(把上游关掉也能命中)。
	server.Close()
	items2, total2, err := client.searchVideos(context.Background(), "黑神话", 1)
	if err != nil || len(items2) != 2 || total2 != 1000 {
		t.Fatalf("cache hit failed: err=%v len=%d total=%d", err, len(items2), total2)
	}
}

func TestBilibiliSearchVideosUpstreamError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "nav") {
			_, _ = w.Write([]byte(`{"code":-101,"data":{"wbi_img":{"img_url":"https://x/a.png","sub_url":"https://x/b.png"}}}`))
			return
		}
		_, _ = w.Write([]byte(`{"code":-412,"message":"请求被拦截"}`))
	}))
	defer server.Close()

	origNav, origSearch := bilibiliNavURL, bilibiliSearchURL
	bilibiliNavURL = server.URL + "/nav"
	bilibiliSearchURL = server.URL + "/search"
	defer func() { bilibiliNavURL, bilibiliSearchURL = origNav, origSearch }()

	client := newBilibiliSearchClient()
	_, _, err := client.searchVideos(context.Background(), "关键词", 1)
	if err == nil || !strings.Contains(err.Error(), "-412") {
		t.Fatalf("expected upstream error -412, got %v", err)
	}
}

func TestHandleBilibiliSearchRequiresToken(t *testing.T) {
	server := &mediaServer{}
	request := httptest.NewRequest(http.MethodGet, "/api/v1/providers/bilibili/search?q=test", nil)
	recorder := httptest.NewRecorder()
	server.handleBilibiliSearch(recorder, request)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", recorder.Code)
	}
	var payload struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if payload.Error.Code != "UNAUTHORIZED" {
		t.Fatalf("code = %q", payload.Error.Code)
	}
}

func TestHandleBilibiliSearchEmptyQuery(t *testing.T) {
	server := &mediaServer{config: AppConfig{AppAPIToken: "tok"}, bilibiliSearch: newBilibiliSearchClient()}
	request := httptest.NewRequest(http.MethodGet, "/api/v1/providers/bilibili/search?q=", nil)
	request.Header.Set("X-App-Token", "tok")
	recorder := httptest.NewRecorder()
	server.handleBilibiliSearch(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	var resp SearchResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.Total != 0 || resp.Items == nil {
		t.Fatalf("empty query must return empty items, got %+v", resp)
	}
}
