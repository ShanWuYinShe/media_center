package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestCrashReportHandler 验证崩溃上报的落盘与校验行为。
func TestCrashReportHandler(t *testing.T) {
	s := &mediaServer{
		config:       AppConfig{CrashReportDir: t.TempDir()},
		crashLimiter: newCrashRateLimiter(crashReportPerMinuteLimit, time.Minute),
	}

	t.Run("accepts valid report", func(t *testing.T) {
		body := `{"packageName":"com.example.mediacenter","appVersionName":"1.2.0","appVersionCode":1002000,"stackTrace":"java.lang.NPE: boom\n\tat a.b.c()"}`
		req := httptest.NewRequest(http.MethodPost, "/api/crash/report", strings.NewReader(body))
		rec := httptest.NewRecorder()
		s.handleCrashReport(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
		}
		data, err := os.ReadFile(filepath.Join(s.config.CrashReportDir, "crash.log"))
		if err != nil {
			t.Fatalf("crash.log missing: %v", err)
		}
		if !strings.Contains(string(data), "com.example.mediacenter") {
			t.Errorf("crash.log does not contain report payload: %s", data)
		}
	})

	t.Run("rejects empty stackTrace", func(t *testing.T) {
		body := `{"packageName":"p","stackTrace":""}`
		req := httptest.NewRequest(http.MethodPost, "/api/crash/report", strings.NewReader(body))
		rec := httptest.NewRecorder()
		s.handleCrashReport(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, want 400", rec.Code)
		}
	})

	t.Run("rejects invalid json", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/crash/report", strings.NewReader("not json"))
		rec := httptest.NewRecorder()
		s.handleCrashReport(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, want 400", rec.Code)
		}
	})
}

// TestCrashReportRateLimit 验证同一 IP 超过窗口上限后被拒绝(429)。
func TestCrashReportRateLimit(t *testing.T) {
	s := &mediaServer{
		config:       AppConfig{CrashReportDir: t.TempDir()},
		crashLimiter: newCrashRateLimiter(3, time.Minute),
	}
	body := `{"packageName":"p","stackTrace":"boom"}`
	for i := 0; i < 3; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/crash/report", strings.NewReader(body))
		rec := httptest.NewRecorder()
		s.handleCrashReport(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, want 200", i+1, rec.Code)
		}
	}
	req := httptest.NewRequest(http.MethodPost, "/api/crash/report", strings.NewReader(body))
	rec := httptest.NewRecorder()
	s.handleCrashReport(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("4th request: status = %d, want 429", rec.Code)
	}
}

// TestAppendCrashReportRotation 验证日志超过上限时滚动为 crash.log.1。
func TestAppendCrashReportRotation(t *testing.T) {
	dir := t.TempDir()
	line := bytes.Repeat([]byte("x"), 1024)
	big := bytes.Repeat([]byte("y"), maxCrashLogBytes)
	if err := os.WriteFile(filepath.Join(dir, "crash.log"), big, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := appendCrashReport(dir, line); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "crash.log.1")); err != nil {
		t.Errorf("crash.log.1 should exist after rotation: %v", err)
	}
	cur, err := os.ReadFile(filepath.Join(dir, "crash.log"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(cur, line) {
		t.Errorf("rotated crash.log should contain new line")
	}
}
