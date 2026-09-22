package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestAPIV1PrefixNormalization 锁定 /api/v1 规范前缀归一化:独立站约定的
// /api/v1/* 与过渡期旧别名 /api/* 行为完全一致;/healthz 仅存在于根路径
// (探针无版本概念),带前缀请求不得误挂。
func TestAPIV1PrefixNormalization(t *testing.T) {
	s := &mediaServer{}
	get := func(path string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		return rec
	}

	// 业务路由:规范前缀与旧别名同响应(catalog 未启用时 providers 返回空列表)
	for _, path := range []string{"/api/v1/providers", "/api/providers"} {
		rec := get(path)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s: status %d, want 200", path, rec.Code)
		}
		if !strings.Contains(rec.Body.String(), `"items":[]`) {
			t.Fatalf("%s: unexpected body: %s", path, rec.Body.String())
		}
	}

	// 查询串在归一化后必须保留
	rec := get("/api/v1/search?q=test")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"query":"test"`) {
		t.Fatalf("/api/v1/search: status %d body %s", rec.Code, rec.Body.String())
	}

	// 存活探针:根路径 /healthz 可用;/api/v1/healthz 归一为不存在的路由
	rec = get("/healthz")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"status":"ok"`) {
		t.Fatalf("/healthz: status %d body %s", rec.Code, rec.Body.String())
	}
	if rec := get("/api/v1/healthz"); rec.Code != http.StatusNotFound {
		t.Fatalf("/api/v1/healthz: status %d, want 404", rec.Code)
	}
}
