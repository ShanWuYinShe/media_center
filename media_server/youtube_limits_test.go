package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// extractor 返回超过读取上限的 200 响应时必须报错,而不是把超大 body 读进
// 64M 容器的堆里(也可能读到被静默截断的 JSON 再解析失败,错误信息更含糊)。
func TestPostExtractorRejectsOversizedResponse(t *testing.T) {
	oversized := strings.Repeat("x", maxYouTubeExtractBytes+1)
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, oversized)
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	_, err := s.yt.postExtractor(context.Background(), "/search", map[string]any{"query": "q", "limit": 1}, 5*time.Second)
	if err == nil {
		t.Fatal("extractor 响应超过上限时应返回错误")
	}
	if !strings.Contains(err.Error(), "exceeds") {
		t.Fatalf("错误应说明响应超限, got %v", err)
	}
}

func TestPostExtractorAcceptsResponseAtLimit(t *testing.T) {
	atLimit := strings.Repeat("x", maxYouTubeExtractBytes)
	extractor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, atLimit)
	}))
	defer extractor.Close()

	s := newYouTubeTestServer(t, extractor.URL)
	raw, err := s.yt.postExtractor(context.Background(), "/search", map[string]any{"query": "q", "limit": 1}, 5*time.Second)
	if err != nil {
		t.Fatalf("恰好到上限的响应应被接受, got %v", err)
	}
	if len(raw) != len(atLimit) {
		t.Fatalf("应完整读取上限内的响应, got %d bytes, want %d", len(raw), len(atLimit))
	}
}
