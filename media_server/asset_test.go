package main

import (
	"encoding/base64"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestAssetSignerRoundTrip(t *testing.T) {
	signer := NewAssetSigner("secret", time.Hour)
	headers := map[string]string{"Referer": "https://www.bilibili.com"}
	proxyURL := signer.ProxyURL("https://host/api", "https://upstream.example/video.mp4", headers)
	if !strings.HasPrefix(proxyURL, "https://host/api/asset?") {
		t.Fatalf("unexpected proxy url: %s", proxyURL)
	}

	values := queryFromURL(t, proxyURL)
	payload, err := signer.Verify(values.Get("p"), values.Get("sig"))
	if err != nil {
		t.Fatalf("verify minted proxy url: %v", err)
	}
	if payload.URL != "https://upstream.example/video.mp4" {
		t.Fatalf("unexpected upstream url: %s", payload.URL)
	}
	if payload.Headers["Referer"] != "https://www.bilibili.com" {
		t.Fatalf("upstream headers not preserved: %v", payload.Headers)
	}
}

func TestAssetSignerPayloadIsOpaque(t *testing.T) {
	signer := NewAssetSigner("secret", time.Hour)
	values := queryFromURL(t, signer.ProxyURL("https://host/api", "https://upstream.example/video.mp4?token=sensitive", map[string]string{
		"Cookie":  "session=sensitive",
		"Referer": "https://private.example",
	}))

	token, err := base64.RawURLEncoding.DecodeString(values.Get("p"))
	if err != nil {
		t.Fatalf("decode proxy token: %v", err)
	}
	tokenText := string(token)
	for _, secret := range []string{
		"https://upstream.example",
		"token=sensitive",
		"session=sensitive",
		"https://private.example",
		"Cookie",
		"Referer",
	} {
		if strings.Contains(tokenText, secret) {
			t.Fatalf("encrypted proxy token exposed %q in %q", secret, tokenText)
		}
	}
}

func TestAssetSignerRejectsTamperedSignature(t *testing.T) {
	signer := NewAssetSigner("secret", time.Hour)
	values := queryFromURL(t, signer.ProxyURL("https://host/api", "https://upstream.example/a.mp4", nil))
	if _, err := signer.Verify(values.Get("p"), values.Get("sig")+"00"); err == nil {
		t.Fatal("expected tampered signature to be rejected")
	}
}

func TestAssetSignerRejectsExpired(t *testing.T) {
	signer := NewAssetSigner("secret", time.Hour)
	signer.ttl = -time.Minute
	values := queryFromURL(t, signer.ProxyURL("https://host/api", "https://upstream.example/a.mp4", nil))
	if _, err := signer.Verify(values.Get("p"), values.Get("sig")); err != errAssetExpired {
		t.Fatalf("expected expired error, got %v", err)
	}
}

func TestHandleAssetProxiesRangeAndHeaders(t *testing.T) {
	var gotRange, gotReferer, gotUA string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRange = r.Header.Get("Range")
		gotReferer = r.Header.Get("Referer")
		gotUA = r.Header.Get("User-Agent")
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Content-Range", "bytes 0-3/4")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write([]byte("mp4!"))
	}))
	defer upstream.Close()

	signer := NewAssetSigner("secret", time.Hour)
	server := &mediaServer{
		asset:        signer,
		assetClient:  newAssetHTTPClient(),
		deliveryMode: DeliveryProxy,
	}
	proxyURL := signer.ProxyURL("http://example/api", upstream.URL+"/video.mp4", map[string]string{
		"Referer": "https://www.bilibili.com",
	})
	values := queryFromURL(t, proxyURL)

	req := httptest.NewRequest(http.MethodGet, "/api/asset?p="+values.Get("p")+"&sig="+values.Get("sig"), nil)
	req.Header.Set("Range", "bytes=0-3")
	rec := httptest.NewRecorder()
	server.handleAsset(rec, req)

	if rec.Code != http.StatusPartialContent {
		t.Fatalf("expected 206, got %d", rec.Code)
	}
	if gotRange != "bytes=0-3" {
		t.Fatalf("range header not forwarded, got %q", gotRange)
	}
	if gotReferer != "https://www.bilibili.com" {
		t.Fatalf("upstream referer not applied, got %q", gotReferer)
	}
	if gotUA == "" {
		t.Fatal("expected default user-agent to be set")
	}
	if body, _ := io.ReadAll(rec.Result().Body); string(body) != "mp4!" {
		t.Fatalf("unexpected proxied body: %q", body)
	}
	if rec.Header().Get("Content-Range") != "bytes 0-3/4" {
		t.Fatalf("content-range not forwarded: %q", rec.Header().Get("Content-Range"))
	}
}

func TestHandleAssetRejectsBadSignature(t *testing.T) {
	server := &mediaServer{
		asset:        NewAssetSigner("secret", time.Hour),
		assetClient:  newAssetHTTPClient(),
		deliveryMode: DeliveryProxy,
	}
	req := httptest.NewRequest(http.MethodGet, "/api/asset?p=abc&sig=def", nil)
	rec := httptest.NewRecorder()
	server.handleAsset(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for bad signature, got %d", rec.Code)
	}
}

func TestUpstreamHTTPClientsIgnoreProxyEnvironment(t *testing.T) {
	assetTransport, ok := newAssetHTTPClient().Transport.(*http.Transport)
	if !ok {
		t.Fatalf("unexpected asset transport type: %T", newAssetHTTPClient().Transport)
	}
	if assetTransport.Proxy != nil {
		t.Fatal("asset client must not use HTTP(S)_PROXY environment variables")
	}

	provider := NewBilibiliClient(time.Second)
	providerTransport, ok := provider.client.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("unexpected provider transport type: %T", provider.client.Transport)
	}
	if providerTransport.Proxy != nil {
		t.Fatal("provider client must not use HTTP(S)_PROXY environment variables")
	}
}

func TestApplyDeliveryProxyRewritesURLs(t *testing.T) {
	server := &mediaServer{
		asset:        NewAssetSigner("secret", time.Hour),
		deliveryMode: DeliveryProxy,
	}
	details := VideoDetails{
		ThumbnailURL:     "https://i0.hdslb.com/a.jpg",
		ThumbnailHeaders: map[string]string{"Referer": "https://www.bilibili.com"},
		PlaybackOptions: []PlaybackOption{{
			ID:      "p1",
			URL:     "https://upos.example/v.mp4",
			Headers: map[string]string{"Referer": "https://www.bilibili.com"},
		}},
	}
	server.applyDeliveryToDetails("https://host/api", &details)

	if details.ThumbnailDelivery != DeliveryProxy {
		t.Fatalf("expected proxy delivery, got %s", details.ThumbnailDelivery)
	}
	if !strings.HasPrefix(details.ThumbnailURL, "https://host/api/asset?") {
		t.Fatalf("thumbnail not proxied: %s", details.ThumbnailURL)
	}
	if details.ThumbnailHeaders != nil {
		t.Fatalf("proxy thumbnail must not expose upstream headers: %v", details.ThumbnailHeaders)
	}
	option := details.PlaybackOptions[0]
	if option.Delivery != DeliveryProxy || !strings.HasPrefix(option.URL, "https://host/api/asset?") || option.Headers != nil {
		t.Fatalf("playback option not proxied correctly: %+v", option)
	}
}

func TestApplyDeliveryDirectPassthrough(t *testing.T) {
	server := &mediaServer{
		asset:        NewAssetSigner("secret", time.Hour),
		deliveryMode: DeliveryDirect,
	}
	details := VideoDetails{
		ThumbnailURL:     "https://i0.hdslb.com/a.jpg",
		ThumbnailHeaders: map[string]string{"Referer": "https://www.bilibili.com"},
		PlaybackOptions: []PlaybackOption{{
			ID:      "p1",
			URL:     "https://upos.example/v.mp4",
			Headers: map[string]string{"Referer": "https://www.bilibili.com"},
		}},
	}
	server.applyDeliveryToDetails("https://host/api", &details)

	if details.ThumbnailDelivery != DeliveryDirect {
		t.Fatalf("expected direct delivery, got %s", details.ThumbnailDelivery)
	}
	if details.ThumbnailURL != "https://i0.hdslb.com/a.jpg" {
		t.Fatalf("direct thumbnail url should pass through, got %s", details.ThumbnailURL)
	}
	if details.ThumbnailHeaders["Referer"] != "https://www.bilibili.com" {
		t.Fatalf("direct thumbnail must keep companion headers: %v", details.ThumbnailHeaders)
	}
	if details.PlaybackOptions[0].Delivery != DeliveryDirect {
		t.Fatalf("expected direct option delivery")
	}
}

func TestSourceDeliveryMode(t *testing.T) {
	enabled := true
	disabled := false
	sources := []sourceConfig{
		{ID: "bilibili", Enabled: true},
		{ID: "youtube", Enabled: false, Direct: &enabled},
		{ID: "internet-archive", Enabled: false, Direct: &disabled},
	}
	if got := sourceDeliveryMode(sources, "bilibili"); got != DeliveryDirect {
		t.Fatalf("bilibili default should be direct, got %s", got)
	}
	if got := sourceDeliveryMode(sources, "youtube"); got != DeliveryDirect {
		t.Fatalf("explicit direct override should win, got %s", got)
	}
	if got := sourceDeliveryMode(sources, "internet-archive"); got != DeliveryProxy {
		t.Fatalf("explicit direct=false should be proxy, got %s", got)
	}
	if got := sourceDeliveryMode(sources, "unknown"); got != DeliveryProxy {
		t.Fatalf("unknown source should default to proxy, got %s", got)
	}
}

func queryFromURL(t *testing.T, raw string) url.Values {
	t.Helper()
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("parse url %q: %v", raw, err)
	}
	return parsed.Query()
}
