package main

import (
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"time"
)

const proxyUserAgent = bilibiliUserAgent

// stripResponseHeaders 是回源响应里不应原样转发给 App 的头：
// 逐跳头(连接相关)，以及不该泄露给客户端的上游 Set-Cookie。
var stripResponseHeaders = map[string]struct{}{
	"connection":          {},
	"keep-alive":          {},
	"proxy-authenticate":  {},
	"proxy-authorization": {},
	"te":                  {},
	"trailer":             {},
	"transfer-encoding":   {},
	"upgrade":             {},
	"set-cookie":          {},
}

// forwardedRequestHeaders 是需要从 App 透传到上游的请求头（保证 Range/seek 与条件请求正确）。
var forwardedRequestHeaders = []string{
	"Range",
	"If-Range",
	"If-None-Match",
	"If-Modified-Since",
	"Accept",
	"Accept-Encoding",
}

func newAssetHTTPClient() *http.Client {
	// 不设置整体 Timeout：媒体流可能长时间持续传输；改为限定连接/响应头阶段的超时。
	// 服务端可直连所有网络，回源一律直连上游，不配置任何出站代理（Proxy 显式置 nil，
	// 不读取 HTTP(S)_PROXY 环境变量）。
	return &http.Client{Transport: newDirectUpstreamTransport(30*time.Second, true)}
}

func newDirectUpstreamTransport(responseHeaderTimeout time.Duration, disableCompression bool) *http.Transport {
	return &http.Transport{
		Proxy: nil,
		DialContext: (&net.Dialer{
			Timeout:   15 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		ResponseHeaderTimeout: responseHeaderTimeout,
		DisableCompression:    disableCompression,
	}
}

func (s *mediaServer) handleAsset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		s.writeError(w, &apiError{
			Status:  http.StatusMethodNotAllowed,
			Code:    "BAD_REQUEST",
			Message: "asset 代理仅支持 GET/HEAD。",
		})
		return
	}
	payload, err := s.asset.Verify(r.URL.Query().Get("p"), r.URL.Query().Get("sig"))
	if err != nil {
		status := http.StatusForbidden
		message := "asset 代理地址无效。"
		if err == errAssetExpired {
			status = http.StatusGone
			message = "asset 代理地址已过期，请刷新后重试。"
		}
		s.writeError(w, &apiError{Status: status, Code: "BAD_REQUEST", Message: message})
		return
	}
	cacheKey := ""
	if r.Method == http.MethodGet && s.assetCache != nil {
		cacheKey = s.assetCache.Key(payload, r)
		cached, err := s.assetCache.Get(cacheKey)
		if err == nil {
			defer cached.Close()
			copyAssetResponseHeaders(w.Header(), cached.Header())
			w.WriteHeader(cached.Status())
			_, _ = io.Copy(w, cached.Body())
			return
		}
		if err != errAssetCacheMiss {
			log.Printf("asset cache lookup failed: %v", err)
		}
	}

	upstream, err := http.NewRequestWithContext(r.Context(), r.Method, payload.URL, nil)
	if err != nil {
		s.writeError(w, &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: "无法构造上游请求。",
		})
		return
	}
	for key, value := range payload.Headers {
		upstream.Header.Set(key, value)
	}
	if upstream.Header.Get("User-Agent") == "" {
		upstream.Header.Set("User-Agent", proxyUserAgent)
	}
	for _, name := range forwardedRequestHeaders {
		if value := r.Header.Get(name); value != "" {
			upstream.Header.Set(name, value)
		}
	}

	resp, err := s.assetClient.Do(upstream)
	if err != nil {
		s.writeError(w, &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: "无法连接上游资源。",
		})
		return
	}
	defer resp.Body.Close()

	responseHeader := cacheableAssetResponseHeader(resp.Header)
	copyAssetResponseHeaders(w.Header(), responseHeader)
	var recorder *assetCacheRecorder
	if s.assetCache != nil {
		recorder, err = s.assetCache.NewRecorder(cacheKey, resp.StatusCode, responseHeader)
		if err != nil {
			log.Printf("asset cache writer unavailable: %v", err)
		}
	}
	w.WriteHeader(resp.StatusCode)
	if r.Method == http.MethodHead {
		return
	}
	body := io.Reader(resp.Body)
	if recorder != nil {
		body = io.TeeReader(resp.Body, recorder)
	}
	if _, err := io.Copy(w, body); err != nil {
		if recorder != nil {
			recorder.Discard()
		}
		return
	}
	if recorder != nil {
		if err := recorder.Commit(); err != nil {
			log.Printf("asset cache commit failed: %v", err)
		}
	}
}

func cacheableAssetResponseHeader(upstream http.Header) http.Header {
	header := http.Header{}
	for key, values := range upstream {
		if _, skip := stripResponseHeaders[strings.ToLower(key)]; skip {
			continue
		}
		for _, value := range values {
			header.Add(key, value)
		}
	}
	if header.Get("Accept-Ranges") == "" {
		header.Set("Accept-Ranges", "bytes")
	}
	return header
}

func copyAssetResponseHeaders(dst, src http.Header) {
	for key, values := range src {
		for _, value := range values {
			dst.Add(key, value)
		}
	}
}
