package main

import (
	"net/http"
	"strings"
	"testing"
)

// /providers/{provider}/bootstrap 是首页启动聚合端点:一次返回 providers +
// 指定 provider 的分类,替代 App 冷启动串行两次请求。本文件锁定路由分发、
// 空数据形态(数组非 null)、缓存头与 youtube 实时分类分支。
func TestProviderBootstrapHTTP(t *testing.T) {
	t.Run("catalog 未启用时返回空数组而非 null", func(t *testing.T) {
		s := &mediaServer{}
		var body BootstrapResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/bootstrap", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		if got := rec.Header().Get("Cache-Control"); got != "public, max-age=60" {
			t.Fatalf("Cache-Control = %q", got)
		}
		if body.Provider != "bilibili" {
			t.Fatalf("provider = %q, want bilibili", body.Provider)
		}
		if body.Providers == nil || len(body.Providers) != 0 {
			t.Fatalf("providers = %#v, want empty non-nil slice", body.Providers)
		}
		if body.Categories == nil || len(body.Categories) != 0 {
			t.Fatalf("categories = %#v, want empty non-nil slice", body.Categories)
		}
		raw := rec.Body.String()
		for _, want := range []string{`"providers":[]`, `"categories":[]`} {
			if !strings.Contains(raw, want) {
				t.Fatalf("body = %s, want %s", raw, want)
			}
		}
	})

	t.Run("有 catalog 数据时同时返回 providers 与分类", func(t *testing.T) {
		c := openTestCatalog(t)
		mustUpsert(t, c, "bilibili", "BV1boot", "启动聚合视频", 120)
		approveTo(t, c, "movies", "bilibili", "BV1boot")

		s := &mediaServer{
			catalog: c,
			categories: []ContentCategory{
				{ID: "movies", Title: "电影", Description: "电影分类"},
			},
			categoriesByID: map[string]ContentCategory{
				"movies": {ID: "movies", Title: "电影", Description: "电影分类"},
			},
		}
		var body BootstrapResponse
		rec := getJSON(t, s, "/api/v1/providers/bilibili/bootstrap", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
		}
		if len(body.Providers) != 1 || body.Providers[0].ID != "bilibili" {
			t.Fatalf("providers = %#v", body.Providers)
		}
		if len(body.Categories) != 1 || body.Categories[0].ID != "movies" || body.Categories[0].Title != "电影" {
			t.Fatalf("categories = %#v", body.Categories)
		}
	})

	t.Run("youtube 走实时分类映射并出现在 providers 中", func(t *testing.T) {
		extractorURL := "http://vod_extractor:8790"
		s := &mediaServer{
			config: AppConfig{VODExtractorURL: extractorURL},
			yt:     newYTClient(extractorURL),
		}
		var body BootstrapResponse
		rec := getJSON(t, s, "/api/v1/providers/youtube/bootstrap", &body)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
		}
		if len(body.Providers) != 1 || body.Providers[0].ID != "youtube" {
			t.Fatalf("providers = %#v", body.Providers)
		}
		wantCategories := publicYouTubeCategories()
		if len(body.Categories) != len(wantCategories) || len(wantCategories) == 0 {
			t.Fatalf("categories = %#v, want %d", body.Categories, len(wantCategories))
		}
	})
}
