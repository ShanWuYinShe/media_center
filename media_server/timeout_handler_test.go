package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAPITimeoutHandlerReturnsJSONEnvelopeOnTimeout(t *testing.T) {
	// handler 等 ctx 取消后才写:验证超时路径丢弃晚到写入,且不会二次写真实响应。
	h := newAPITimeoutHandler(30*time.Millisecond, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(2 * time.Second):
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"late":true}`))
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers", nil))

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("超时应返回 503, got %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("超时响应必须是 JSON 包络, got Content-Type %q", ct)
	}
	if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("错误响应禁止缓存, got Cache-Control %q", cc)
	}
	var env apiErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatalf("超时响应不是合法 JSON 包络: %v (body=%q)", err, rec.Body.String())
	}
	if env.Error.Code != "TIMEOUT" {
		t.Fatalf("超时错误码应为 TIMEOUT, got %q", env.Error.Code)
	}
	if env.Error.Message == "" {
		t.Fatal("超时错误应有用户可读 message")
	}
}

func TestAPITimeoutHandlerCopiesResponseWhenCompleted(t *testing.T) {
	h := newAPITimeoutHandler(time.Second, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Test", "yes")
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/v1/x", nil))

	if rec.Code != http.StatusCreated {
		t.Fatalf("未超时应原样透传状态码, got %d", rec.Code)
	}
	if got := rec.Header().Get("X-Test"); got != "yes" {
		t.Fatalf("未超时应复制响应头, got X-Test=%q", got)
	}
	if got := rec.Body.String(); got != `{"status":"ok"}` {
		t.Fatalf("未超时应原样复制响应体, got %q", got)
	}
}

func TestAPITimeoutHandlerRecoversPanic(t *testing.T) {
	h := newAPITimeoutHandler(time.Second, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic("boom")
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/providers", nil))

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("panic 应回 500, got %d", rec.Code)
	}
	var env apiErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatalf("panic 响应应为 JSON 包络: %v", err)
	}
	if env.Error.Code != "INTERNAL_ERROR" {
		t.Fatalf("panic 错误码应为 INTERNAL_ERROR, got %q", env.Error.Code)
	}
}
