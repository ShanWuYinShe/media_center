package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 本文件覆盖 App 端唯一依赖的两个目录浏览接口的 HTTP 层:
//   GET /api/v1/providers/{provider}/categories
//   GET /api/v1/providers/{provider}/categories/{catID}/videos
//
// 此前这两条路由只有 catalog 取数层的测试(catalog_test.go),handler 本身
// 覆盖率为 0——路由分发、分页参数校验、404/500 分支、缓存头都没被验证。
// App 启动即调这两个接口,回归会直接表现为首页空白。

func getJSON(t *testing.T, s *mediaServer, path string, out any) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	if out != nil && rec.Body.Len() > 0 {
		if err := json.Unmarshal(rec.Body.Bytes(), out); err != nil {
			t.Fatalf("decode %s: %v (body=%s)", path, err, rec.Body.String())
		}
	}
	return rec
}

// approveTo 把一个已 upsert 的视频加入分类并置为 approved。
func approveTo(t *testing.T, c *Catalog, cat, provider, nativeID string) {
	t.Helper()
	if _, err := c.AddPendingMember(cat, provider, nativeID, 10); err != nil {
		t.Fatalf("add %s/%s: %v", cat, nativeID, err)
	}
	if _, err := c.SetMemberStatus(cat, provider, nativeID, "approved", "tester"); err != nil {
		t.Fatalf("approve %s/%s: %v", cat, nativeID, err)
	}
}

func TestProviderCategoriesHTTP(t *testing.T) {
	t.Run("catalog 未启用返回空数组而非 null", func(t *testing.T) {
		s := &mediaServer{}
		var body CategoriesResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if got := rec.Header().Get("Cache-Control"); got != "public, max-age=60" {
			t.Fatalf("Cache-Control = %q, want public, max-age=60", got)
		}
		if body.Items == nil || len(body.Items) != 0 {
			t.Fatalf("items = %#v, want empty non-nil slice", body.Items)
		}
		// App 侧按数组解析,绝不能是 null
		if raw := rec.Body.String(); !strings.Contains(raw, `"items":[]`) {
			t.Fatalf("body = %s, want empty items array", raw)
		}
	})

	t.Run("未定义分类回退为带 id 的占位标题", func(t *testing.T) {
		c := openTestCatalog(t)
		mustUpsert(t, c, "bilibili", "BV1ghost", "幽灵分类视频", 60)
		approveTo(t, c, "ghost", "bilibili", "BV1ghost")

		s := &mediaServer{
			catalog:        c,
			categoriesByID: map[string]ContentCategory{},
		}
		var body CategoriesResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if len(body.Items) != 1 {
			t.Fatalf("items = %#v, want 1 item", body.Items)
		}
		if body.Items[0].ID != "ghost" {
			t.Fatalf("id = %q, want ghost", body.Items[0].ID)
		}
		if body.Items[0].Title != "未定义分类 ghost" {
			t.Fatalf("title = %q, want 未定义分类 ghost", body.Items[0].Title)
		}
	})

	t.Run("已定义分类用配置标题而非 id", func(t *testing.T) {
		c := openTestCatalog(t)
		mustUpsert(t, c, "bilibili", "BV1m", "电影", 60)
		approveTo(t, c, "movies", "bilibili", "BV1m")

		s := &mediaServer{
			catalog: c,
			categoriesByID: map[string]ContentCategory{
				"movies": {ID: "movies", Title: "电影", Description: "d"},
			},
		}
		var body CategoriesResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories", &body)
		if rec.Code != http.StatusOK || len(body.Items) != 1 {
			t.Fatalf("status=%d items=%#v", rec.Code, body.Items)
		}
		if body.Items[0].Title != "电影" {
			t.Fatalf("title = %q, want 电影", body.Items[0].Title)
		}
	})

	t.Run("catalog 故障返回 500 且不泄漏内部错误", func(t *testing.T) {
		c := openTestCatalog(t)
		// 关闭底层 DB 制造真实查询错误
		if err := c.Close(); err != nil {
			t.Fatalf("close: %v", err)
		}
		s := &mediaServer{catalog: c, categoriesByID: map[string]ContentCategory{}}
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories", nil)
		if rec.Code != http.StatusInternalServerError {
			t.Fatalf("status = %d, want 500", rec.Code)
		}
		if !strings.Contains(rec.Body.String(), "INTERNAL_ERROR") {
			t.Fatalf("body = %s, want INTERNAL_ERROR code", rec.Body.String())
		}
	})
}

func TestProviderCategoryVideosHTTP(t *testing.T) {
	newServer := func(t *testing.T) (*mediaServer, *Catalog) {
		t.Helper()
		c := openTestCatalog(t)
		mustUpsert(t, c, "bilibili", "BV1one", "第一个视频", 120)
		approveTo(t, c, "movies", "bilibili", "BV1one")
		return &mediaServer{
			catalog: c,
			categoriesByID: map[string]ContentCategory{
				"movies": {ID: "movies", Title: "电影", Description: "精选"},
			},
		}, c
	}

	t.Run("返回分页视频与分类信息", func(t *testing.T) {
		s, _ := newServer(t)
		var body VideosResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories/movies/videos", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if got := rec.Header().Get("Cache-Control"); got != "public, max-age=60" {
			t.Fatalf("Cache-Control = %q", got)
		}
		if body.Total != 1 || len(body.Items) != 1 {
			t.Fatalf("total=%d items=%#v", body.Total, body.Items)
		}
		if body.Category == nil || body.Category.ID != "movies" || body.Category.Title != "电影" {
			t.Fatalf("category = %#v", body.Category)
		}
		if body.Items[0].ID != "BV1one" || body.Items[0].Title != "第一个视频" {
			t.Fatalf("item = %#v", body.Items[0])
		}
		if body.Page != 1 || body.PageSize != 20 {
			t.Fatalf("page=%d pageSize=%d, want defaults 1/20", body.Page, body.PageSize)
		}
	})

	t.Run("未知分类返回 404", func(t *testing.T) {
		s, _ := newServer(t)
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories/nope/videos", nil)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("status = %d, want 404", rec.Code)
		}
		if !strings.Contains(rec.Body.String(), "NOT_FOUND") {
			t.Fatalf("body = %s", rec.Body.String())
		}
	})

	t.Run("非法分页参数返回 400 且不触达 catalog", func(t *testing.T) {
		s, _ := newServer(t)
		for _, q := range []string{"page=0", "page=201", "page=12abc", "pageSize=0", "pageSize=41", "pageSize=x"} {
			rec := getJSON(t, s, "/api/v1/providers/bilibili/categories/movies/videos?"+q, nil)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("%s: status = %d, want 400", q, rec.Code)
			}
			if !strings.Contains(rec.Body.String(), "BAD_REQUEST") {
				t.Fatalf("%s: body = %s", q, rec.Body.String())
			}
		}
	})

	t.Run("catalog 未启用返回空列表但保留分类信息", func(t *testing.T) {
		s := &mediaServer{
			categoriesByID: map[string]ContentCategory{
				"movies": {ID: "movies", Title: "电影", Description: "精选"},
			},
		}
		var body VideosResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories/movies/videos", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if body.Total != 0 || body.Items == nil || len(body.Items) != 0 {
			t.Fatalf("total=%d items=%#v, want 0 and empty non-nil", body.Total, body.Items)
		}
		if body.Category == nil || body.Category.ID != "movies" {
			t.Fatalf("category = %#v", body.Category)
		}
	})

	t.Run("显式分页参数被透传", func(t *testing.T) {
		s, _ := newServer(t)
		var body VideosResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/categories/movies/videos?page=2&pageSize=5", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if body.Page != 2 || body.PageSize != 5 {
			t.Fatalf("page=%d pageSize=%d, want 2/5", body.Page, body.PageSize)
		}
	})
}

// TestProviderCatalogRouteDispatch 锁定路由分发:旧别名 /api/* 与规范前缀
// /api/v1/* 行为一致,且畸形路径一律 404(不得 panic 或误挂到其它 handler)。
func TestProviderCatalogRouteDispatch(t *testing.T) {
	s := &mediaServer{}
	cases := []struct {
		path string
		want int
	}{
		{"/api/providers/bilibili/categories", http.StatusOK},
		{"/api/v1/providers/bilibili/categories", http.StatusOK},
		{"/api/providers/bilibili/categories/movies/videos", http.StatusNotFound}, // 分类未定义 → 404
		{"/api/v1/providers/bilibili/categories/movies/videos", http.StatusNotFound},
		{"/api/providers/", http.StatusNotFound},
		{"/api/providers/bilibili", http.StatusNotFound},
		{"/api/providers/bilibili/categories/movies", http.StatusNotFound},
		{"/api/providers/bilibili/categories/movies/videos/extra", http.StatusNotFound},
	}
	for _, tc := range cases {
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, tc.path, nil))
		if rec.Code != tc.want {
			t.Fatalf("%s: status = %d, want %d (body=%s)", tc.path, rec.Code, tc.want, rec.Body.String())
		}
	}
}
