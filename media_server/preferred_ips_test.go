package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// preferred_ips_test.go — 优选 IP 候选池的纯逻辑与端点契约。

func TestIsValidIPv4Literal(t *testing.T) {
	valid := []string{"1.1.1.1", "103.31.4.1", "255.255.255.255", "104.16.0.1"}
	for _, value := range valid {
		if !isValidIPv4Literal(value) {
			t.Fatalf("%q should be valid", value)
		}
	}
	invalid := []string{
		"", "1.1.1", "1.1.1.1.1", "256.1.1.1", "1.1.1.256",
		"01.1.1.1", "1.1.1.-1", "1.1.1.a", "abc",
		"2606:4700:3031::6815:561", // IPv6 不在本端点支持范围
	}
	for _, value := range invalid {
		if isValidIPv4Literal(value) {
			t.Fatalf("%q should be invalid", value)
		}
	}
}

func TestPreferredIPv4PatternExtractsFromMixedText(t *testing.T) {
	// 真实公共源形态:纯列表 / CSV / JSON / 夹带说明文字，都要能抽出来。
	body := "104.27.39.54 104.25.207.186\n# comment 1.2.3.4\n{\"ip\":\"104.21.55.165\"}\nnot-an-ip 999.1.1.1"
	got := preferredIPv4Pattern.FindAllString(body, -1)
	want := []string{"104.27.39.54", "104.25.207.186", "1.2.3.4", "104.21.55.165", "999.1.1.1"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("index %d: got %q want %q", index, got[index], want[index])
		}
	}
}

func TestPreferredIPPoolCollectDedupesAndKeepsSeedFallback(t *testing.T) {
	// 公共源全部失败时:必须仍返回种子池(否则端点会空转，客户端拿不到 IP)。
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer server.Close()

	originalSources := preferredIPSources
	preferredIPSources = []string{server.URL}
	defer func() { preferredIPSources = originalSources }()

	pool := newPreferredIPPool()
	items, sourceOK := pool.collect(context.Background())
	if sourceOK {
		t.Fatalf("source should be reported as failed")
	}
	if len(items) == 0 {
		t.Fatalf("collect must fall back to seed pool")
	}
	if items[0] != preferredIPSeeds[0] {
		t.Fatalf("fallback should start with seeds, got %q", items[0])
	}
	// 去重:种子池本身无重复，这里再断言一次没有重复项。
	seen := map[string]bool{}
	for _, ip := range items {
		if seen[ip] {
			t.Fatalf("duplicate ip %q", ip)
		}
		seen[ip] = true
	}
}

func TestPreferredIPPoolCollectPutsRemoteFirstAndDedupes(t *testing.T) {
	// 公共源成功时:远端 IP 排在种子之前，且与种子重复的项只保留一次。
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("10.9.8.7\n" + preferredIPSeeds[0] + "\n10.9.8.7\n"))
	}))
	defer server.Close()

	originalSources := preferredIPSources
	preferredIPSources = []string{server.URL}
	defer func() { preferredIPSources = originalSources }()

	pool := newPreferredIPPool()
	items, sourceOK := pool.collect(context.Background())
	if !sourceOK {
		t.Fatalf("source should succeed")
	}
	if items[0] != "10.9.8.7" {
		t.Fatalf("remote ip should come first, got %q", items[0])
	}
	// 种子池第一项紧随其后，且只出现一次。
	count := 0
	for _, ip := range items {
		if ip == preferredIPSeeds[0] {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("seed ip should appear exactly once, got %d", count)
	}
}

func TestPreferredIPsPayloadAlwaysHasItems(t *testing.T) {
	// 端点契约:即使池子还没刷新，也必须给出可用候选(种子兜底)。
	server := &mediaServer{}
	payload := server.preferredIPsPayloadFor()
	if len(payload.Items) == 0 {
		t.Fatalf("payload must never be empty")
	}
	if payload.CacheSeconds <= 0 {
		t.Fatalf("cacheSeconds must be positive, got %d", payload.CacheSeconds)
	}
	for _, ip := range payload.Items {
		if !isValidIPv4Literal(ip) {
			t.Fatalf("payload contains invalid ip %q", ip)
		}
	}
}

func TestHandlePreferredIPsContract(t *testing.T) {
	server := &mediaServer{preferredIPs: newPreferredIPPool()}
	request := httptest.NewRequest(http.MethodGet, "/api/v1/preferred-ips", nil)
	recorder := httptest.NewRecorder()
	server.handlePreferredIPs(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if got := recorder.Header().Get("Cache-Control"); got == "" {
		t.Fatalf("Cache-Control must be set")
	}
	var payload preferredIPsPayload
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	if len(payload.Items) == 0 {
		t.Fatalf("items must not be empty")
	}
}

func TestPreferredIPPoolSnapshotIsCopy(t *testing.T) {
	// 读路径必须拿副本:调用方改返回值不能污染池子(并发下会读到半更新的切片)。
	pool := newPreferredIPPool()
	items, _ := pool.snapshot()
	items[0] = "0.0.0.0"
	again, _ := pool.snapshot()
	if again[0] == "0.0.0.0" {
		t.Fatalf("snapshot must return a copy")
	}
}
