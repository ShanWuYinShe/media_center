package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestRequestLogMiddlewareRecoversPanic 锁定 panic 恢复:handler panic 不再
// 半途断连(net/http 内建 recover 只保进程不死),客户端拿到结构化 500 包络。
func TestRequestLogMiddlewareRecoversPanic(t *testing.T) {
	boom := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { panic("boom") })
	srv := requestLogMiddleware(boom)
	req := httptest.NewRequest(http.MethodGet, "/api/providers", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req) // 不得向上抛 panic
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("panic should become 500, got %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "INTERNAL_ERROR") {
		t.Fatalf("panic response should use error envelope: %s", rec.Body.String())
	}
}

// TestRequestLogMiddlewareNormalRequestsUnaffected 常规路径不受恢复逻辑影响。
func TestRequestLogMiddlewareNormalRequestsUnaffected(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	srv := requestLogMiddleware(ok)
	req := httptest.NewRequest(http.MethodGet, "/api/providers", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"ok":true`) {
		t.Fatalf("normal request broken: %d %s", rec.Code, rec.Body.String())
	}
}

// readerFromRecorder 模拟底层 ResponseWriter 支持 ReaderFrom(真 http.response
// 如此,可走 sendfile);httptest.ResponseRecorder 本身不支持。
type readerFromRecorder struct {
	*httptest.ResponseRecorder
	readFromCalled bool
}

func (r *readerFromRecorder) ReadFrom(src io.Reader) (int64, error) {
	r.readFromCalled = true
	return io.Copy(r.ResponseRecorder.Body, src)
}

// TestStatusRecorderReadFromDelegates 锁定 ReadFrom 透传:底层支持 ReaderFrom
// 时委派(保留 sendfile 零拷贝通路),字节数照记。
func TestStatusRecorderReadFromDelegates(t *testing.T) {
	base := &readerFromRecorder{ResponseRecorder: httptest.NewRecorder()}
	rec := &statusRecorder{ResponseWriter: base, status: http.StatusOK}
	n, err := rec.ReadFrom(strings.NewReader("hello apk"))
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if n != 9 || !base.readFromCalled {
		t.Fatalf("n=%d readFromCalled=%v", n, base.readFromCalled)
	}
	if rec.bytes != 9 {
		t.Fatalf("bytes=%d, want 9", rec.bytes)
	}
}

// TestStatusRecorderReadFromFallback 底层不支持 ReaderFrom 时退化拷贝,
// 且不得因自身实现 ReadFrom 而递归。
func TestStatusRecorderReadFromFallback(t *testing.T) {
	rec := &statusRecorder{ResponseWriter: httptest.NewRecorder(), status: http.StatusOK}
	n, err := rec.ReadFrom(strings.NewReader("hello apk"))
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if n != 9 || rec.bytes != 9 {
		t.Fatalf("n=%d bytes=%d, want 9/9", n, rec.bytes)
	}
}
