package main

import (
	"encoding/json"
	"testing"
)

func TestThumbnailVariantsBilibili(t *testing.T) {
	raw := "https://i0.hdslb.com/bfs/archive/abc.jpg"
	card, large := thumbnailVariants(raw)
	if card != raw+bilibiliCardThumbSuffix {
		t.Fatalf("card = %q", card)
	}
	if large != raw+bilibiliLargeThumbSuffix {
		t.Fatalf("large = %q", large)
	}

	// 已带 @ 处理参数时先剥掉,避免重复追加。
	card, large = thumbnailVariants(raw + "@100w.jpg")
	if card != raw+bilibiliCardThumbSuffix || large != raw+bilibiliLargeThumbSuffix {
		t.Fatalf("suffixed input: card=%q large=%q", card, large)
	}
}

func TestThumbnailVariantsPassthroughForUnknownHost(t *testing.T) {
	raw := "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg"
	card, large := thumbnailVariants(raw)
	if card != raw || large != raw {
		t.Fatalf("passthrough: card=%q large=%q", card, large)
	}
	if card, large := thumbnailVariants("  "); card != "" || large != "" {
		t.Fatalf("empty input: card=%q large=%q", card, large)
	}
}

func TestYouTubeCardThumbnailPicksSmallestCardWidth(t *testing.T) {
	var entry ytdlpEntry
	if err := json.Unmarshal([]byte(`{"thumbnail":"https://i/single.jpg","thumbnails":[
		{"url":"https://i/mq.jpg","width":320},
		{"url":"https://i/hq.jpg","width":480},
		{"url":"https://i/maxres.jpg","width":1280}]}`), &entry); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got := youtubeCardThumbnail(entry); got != "https://i/hq.jpg" {
		t.Fatalf("card thumbnail = %q, want hq 480w", got)
	}
	if got := bestThumbnailURL(entry); got != "https://i/maxres.jpg" {
		t.Fatalf("large thumbnail = %q, want maxres", got)
	}

	// 没有 >=480 时退回最大一档;完全没有列表时退回单值 thumbnail。
	var smallOnly ytdlpEntry
	if err := json.Unmarshal([]byte(`{"thumbnail":"https://i/fallback.jpg","thumbnails":[
		{"url":"https://i/default.jpg","width":120}]}`), &smallOnly); err != nil {
		t.Fatalf("unmarshal small: %v", err)
	}
	if got := youtubeCardThumbnail(smallOnly); got != "https://i/default.jpg" {
		t.Fatalf("small-only card thumbnail = %q", got)
	}
	var noList ytdlpEntry
	if err := json.Unmarshal([]byte(`{"thumbnail":"https://i/fallback.jpg"}`), &noList); err != nil {
		t.Fatalf("unmarshal fallback: %v", err)
	}
	if got := youtubeCardThumbnail(noList); got != "https://i/fallback.jpg" {
		t.Fatalf("fallback card thumbnail = %q", got)
	}
}
