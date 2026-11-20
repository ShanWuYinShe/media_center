package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// youtube_stream_test.go — 播放流中转的映射管理与 resolve 改写契约。

func TestStreamProxyRegisterServe(t *testing.T) {
	// 假 googlevideo:支持 Range(简化实现:按 start 截断)。
	var upstreamBody = []byte("0123456789ABCDEF")
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rangeHeader := r.Header.Get("Range")
		if strings.HasPrefix(rangeHeader, "bytes=") {
			start := 0
			_, _ = fmt.Sscanf(rangeHeader, "bytes=%d-", &start)
			if start < 0 || start > len(upstreamBody) {
				http.Error(w, "bad range", http.StatusRequestedRangeNotSatisfiable)
				return
			}
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, len(upstreamBody)-1, len(upstreamBody)))
			w.Header().Set("Accept-Ranges", "bytes")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(upstreamBody[start:])
			return
		}
		_, _ = w.Write(upstreamBody)
	}))
	defer upstream.Close()

	p := newYouTubeStreamProxy()
	token := p.register(upstream.URL + "/videoplayback?sig=xyz")
	if token == "" || len(token) != 32 {
		t.Fatalf("token = %q", token)
	}

	// 无 Range:全量 200。
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/providers/youtube/stream/"+token, nil)
	p.serve(rec, req, token)
	if rec.Code != http.StatusOK || rec.Body.String() != string(upstreamBody) {
		t.Fatalf("full stream = %d %q", rec.Code, rec.Body.String())
	}

	// 带 Range:206 + Content-Range 透传。
	rec2 := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodGet, "/api/providers/youtube/stream/"+token, nil)
	req2.Header.Set("Range", "bytes=4-")
	p.serve(rec2, req2, token)
	if rec2.Code != http.StatusPartialContent {
		t.Fatalf("range status = %d", rec2.Code)
	}
	if rec2.Body.String() != "456789ABCDEF" {
		t.Fatalf("range body = %q", rec2.Body.String())
	}
	if cr := rec2.Header().Get("Content-Range"); cr != "bytes 4-15/16" {
		t.Fatalf("Content-Range = %q", cr)
	}
}

func TestStreamProxyUnknownAndExpiredToken(t *testing.T) {
	p := newYouTubeStreamProxy()
	rec := httptest.NewRecorder()
	p.serve(rec, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/stream/unknown", nil), "unknown")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("unknown token = %d, want 404", rec.Code)
	}

	// 过期 token:手工注入已过期条目。
	expired := p.register("https://up.example/x")
	p.mu.Lock()
	e := p.entries[expired]
	e.expiresAt = time.Now().Add(-time.Minute)
	p.entries[expired] = e
	p.mu.Unlock()
	rec2 := httptest.NewRecorder()
	p.serve(rec2, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/stream/"+expired, nil), expired)
	if rec2.Code != http.StatusNotFound {
		t.Fatalf("expired token = %d, want 404", rec2.Code)
	}
}

func TestStreamProxyLRUCap(t *testing.T) {
	p := newYouTubeStreamProxy()
	// 填满容量后再注册新的:最早的 token 被淘汰。
	first := p.register("https://up.example/first")
	for i := 0; i < youtubeStreamMaxEntries; i++ {
		p.register(fmt.Sprintf("https://up.example/%d", i))
	}
	if p.lookup(first) != "" {
		t.Fatalf("oldest token must be evicted")
	}
}

func TestStreamProxyRegisterEmptyURL(t *testing.T) {
	p := newYouTubeStreamProxy()
	if p.register("") != "" {
		t.Fatalf("empty upstream must yield empty token")
	}
}

// resolve 改写契约:publicBase 配置后,直链被改写为代理地址(HLS 选项被剔除、
// availableQualities 同步过滤、AudioURL 同样改写);改写后的 token 立即可拉流。
func TestResolveRewritesOptionsForProxy(t *testing.T) {
	// 假 googlevideo:响应固定字节并支持 Range。
	gv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		_, _ = w.Write([]byte("STREAMBYTES"))
	}))
	defer gv.Close()

	// 假 extractor:返回 HLS + 直链视频轨 + 独立音轨。
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"id":"dQw4w9WgXcQ","title":"t","duration":10,"thumbnail":"","formats":[
			{"format_id":"hls","url":"https://gv.example/hls.m3u8","ext":"mp4","height":720,"fps":30,"vcodec":"avc1","acodec":"mp4a","protocol":"m3u8"},
			{"format_id":"137","url":"` + gv.URL + `/video137","ext":"mp4","height":1080,"fps":30,"vcodec":"avc1","acodec":"none","protocol":"https"},
			{"format_id":"140","url":"` + gv.URL + `/audio140","ext":"m4a","height":0,"fps":0,"vcodec":"none","acodec":"mp4a","protocol":"https"}
		]}`))
	}))
	defer extractor.Close()

	swapResolveCache(t)
	s := &mediaServer{
		config:                AppConfig{AppAPIToken: "tok"},
		youtubeResolveLimiter: newIPWindowLimiter(1000, time.Minute),
		yt:                    newYTClient(extractor.URL),
		youtubeStream:         newYouTubeStreamProxy(),
	}
	s.yt.publicBase = "https://media.example/api/v1"

	rec := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec.Code != http.StatusOK {
		t.Fatalf("resolve = %d body=%s", rec.Code, rec.Body.String())
	}
	var result PlaybackResultResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(result.Options) != 1 {
		t.Fatalf("options = %d, want 1 (HLS dropped)", len(result.Options))
	}
	opt := result.Options[0]
	if opt.QN != 1080 || opt.AudioURL == "" {
		t.Fatalf("option = %+v", opt)
	}
	if !strings.HasPrefix(opt.URL, "https://media.example/api/v1/providers/youtube/stream/") ||
		!strings.HasPrefix(opt.AudioURL, "https://media.example/api/v1/providers/youtube/stream/") {
		t.Fatalf("urls must be rewritten: %q / %q", opt.URL, opt.AudioURL)
	}
	// HLS 档位(720)被剔除,qualities 只剩 1080。
	if len(result.AvailableQualities) != 1 || result.AvailableQualities[0].QN != 1080 {
		t.Fatalf("qualities = %+v", result.AvailableQualities)
	}

	// 改写后的 video token 立即可拉流,拿到的是上游字节。
	token := strings.TrimPrefix(opt.URL, "https://media.example/api/v1/providers/youtube/stream/")
	rec2 := httptest.NewRecorder()
	s.handleYouTubeStream(rec2, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/stream/"+token, nil), token)
	if rec2.Code != http.StatusOK || rec2.Body.String() != "STREAMBYTES" {
		t.Fatalf("stream = %d %q", rec2.Code, rec2.Body.String())
	}
	// audio token 同样可用。
	audioToken := strings.TrimPrefix(opt.AudioURL, "https://media.example/api/v1/providers/youtube/stream/")
	rec3 := httptest.NewRecorder()
	s.handleYouTubeStream(rec3, httptest.NewRequest(http.MethodGet, "/api/providers/youtube/stream/"+audioToken, nil), audioToken)
	if rec3.Code != http.StatusOK || rec3.Body.String() != "STREAMBYTES" {
		t.Fatalf("audio stream = %d %q", rec3.Code, rec3.Body.String())
	}

	// 内存缓存命中路径(第二次 resolve)同样得到改写后的 URL。
	rec4 := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec4.Code != http.StatusOK || !strings.Contains(rec4.Body.String(), "/providers/youtube/stream/") {
		t.Fatalf("cached resolve must also rewrite: %d %s", rec4.Code, rec4.Body.String())
	}
}

func TestResolveKeepsOriginalURLsWithoutPublicBase(t *testing.T) {
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"id":"dQw4w9WgXcQ","title":"t","duration":10,"thumbnail":"","formats":[
			{"format_id":"18","url":"https://gv.example/x.mp4","ext":"mp4","height":360,"fps":30,"vcodec":"avc1","acodec":"mp4a","protocol":"https"}
		]}`))
	}))
	defer extractor.Close()

	swapResolveCache(t)
	// 未设 publicBase(本地开发):保持原始直链。
	s := newResolveTestServer(t, extractor.URL)
	rec := resolveRequest(t, s, "dQw4w9WgXcQ")
	if rec.Code != http.StatusOK {
		t.Fatalf("resolve = %d", rec.Code)
	}
	var result PlaybackResultResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(result.Options) != 1 || result.Options[0].URL != "https://gv.example/x.mp4" {
		t.Fatalf("options must keep original urls: %+v", result.Options)
	}
}
