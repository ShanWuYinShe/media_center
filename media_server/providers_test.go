package main

import (
	"testing"
)

// /providers 是 App 源切换的唯一来源。youtube 内容只存在于实时搜索、永不写
// catalog,因此不能只按 catalog 推导源列表,否则 App 永远看不到 YouTube。
func TestProvidersIncludesYouTubeWhenRealtimeConfigured(t *testing.T) {
	t.Run("配置 vod_extractor 时下发 youtube", func(t *testing.T) {
		s := &mediaServer{config: AppConfig{VODExtractorURL: "http://vod_extractor:8790"}}
		var body ProvidersResponse
		rec := getJSON(t, s, "/api/v1/providers", &body)
		if rec.Code != 200 {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		found := false
		for _, item := range body.Items {
			if item.ID == "youtube" {
				found = true
				if item.Name == "" {
					t.Fatalf("youtube name is empty")
				}
			}
		}
		if !found {
			t.Fatalf("providers = %#v, want youtube included", body.Items)
		}
	})

	t.Run("未配置动态通道时不下发 youtube", func(t *testing.T) {
		s := &mediaServer{}
		var body ProvidersResponse
		getJSON(t, s, "/api/v1/providers", &body)
		for _, item := range body.Items {
			if item.ID == "youtube" {
				t.Fatalf("providers = %#v, did not expect youtube", body.Items)
			}
		}
	})
}
