package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestApplyCurationBlocksAndPins(t *testing.T) {
	s := &mediaServer{
		idCodec: NewIDCodec("test-secret"),
		curation: CurationConfig{categories: map[string]CategoryCuration{
			"movies": {
				BlockIDs:           map[string]struct{}{"bad-id": {}},
				BlockTitleContains: []string{"预告"},
				PinIDs:             []string{"good-2"},
			},
		}},
	}
	items := []VideoSummary{
		{ID: "good-1", Title: "正片 A"},
		{ID: "bad-id", Title: "正片 B"},
		{ID: "trailer", Title: "电影预告片"},
		{ID: "good-2", Title: "正片 C"},
	}
	out := s.applyCuration(context.Background(), "movies", items)
	// bad-id blocked by ID, trailer blocked by title; good-2 pinned to front.
	if len(out) != 2 {
		t.Fatalf("expected 2 items after curation, got %d: %+v", len(out), out)
	}
	if out[0].ID != "good-2" {
		t.Fatalf("expected good-2 pinned first, got %s", out[0].ID)
	}
	if out[1].ID != "good-1" {
		t.Fatalf("expected good-1 second, got %s", out[1].ID)
	}
}

func TestApplyCurationNoRulesIsNoop(t *testing.T) {
	s := &mediaServer{curation: CurationConfig{categories: map[string]CategoryCuration{}}}
	items := []VideoSummary{{ID: "a"}, {ID: "b"}}
	out := s.applyCuration(context.Background(), "movies", items)
	if len(out) != 2 {
		t.Fatalf("expected passthrough when no rules, got %d", len(out))
	}
}

func TestApplyCurationUnknownPinIsSkipped(t *testing.T) {
	// A pin ID that is neither in the list nor a decodable provider ID must be
	// skipped gracefully (no panic, no crash), leaving the rest intact.
	s := &mediaServer{
		idCodec: NewIDCodec("test-secret"),
		curation: CurationConfig{categories: map[string]CategoryCuration{
			"movies": {BlockIDs: map[string]struct{}{}, PinIDs: []string{"not-a-real-id"}},
		}},
	}
	items := []VideoSummary{{ID: "a", Title: "A"}, {ID: "b", Title: "B"}}
	out := s.applyCuration(context.Background(), "movies", items)
	if len(out) != 2 {
		t.Fatalf("expected 2 items when pin unresolved, got %d", len(out))
	}
}

func TestApplyCurationMatchesStableNativeIDs(t *testing.T) {
	// 审校用稳定的原生 ID(provider:nativeID / 裸 BVID)配置,应能匹配到
	// 列表里携带的公开签名 ID(EncodeVideoID 的产物),不依赖签名密钥。
	codec := NewIDCodec("test-secret")
	keepID := codec.EncodeVideoID("BV1keep")
	blockID := codec.EncodeVideoID("BV1block")
	pinID := codec.EncodeVideoID("BV1pin")
	s := &mediaServer{
		idCodec: codec,
		curation: CurationConfig{categories: map[string]CategoryCuration{
			"movies": {
				BlockIDs: map[string]struct{}{"bilibili:BV1block": {}},
				PinIDs:   []string{"BV1pin"},
			},
		}},
	}
	items := []VideoSummary{
		{ID: keepID, Title: "Keep"},
		{ID: blockID, Title: "Block"},
		{ID: pinID, Title: "Pin"},
	}
	out := s.applyCuration(context.Background(), "movies", items)
	if len(out) != 2 {
		t.Fatalf("expected 2 items after native-id block, got %d: %+v", len(out), out)
	}
	if out[0].ID != pinID {
		t.Fatalf("expected video pinned by native BVID first, got %s", out[0].ID)
	}
	for _, it := range out {
		if it.ID == blockID {
			t.Fatalf("video should have been blocked via native key bilibili:BV1block")
		}
	}
}

func TestNativeKeyForms(t *testing.T) {
	codec := NewIDCodec("test-secret")
	s := &mediaServer{idCodec: codec}
	encoded := codec.EncodeVideoID("BV1abc")
	cases := map[string]string{
		encoded:           "bilibili:BV1abc", // 公开签名 ID
		"BV1abc":          "bilibili:BV1abc", // 裸原生 ID
		"bilibili:BV1abc": "bilibili:BV1abc", // provider:nativeID
		"BILIBILI:BV1abc": "bilibili:BV1abc", // provider 大小写不敏感
	}
	for in, want := range cases {
		if got := s.nativeKey(in); got != want {
			t.Errorf("nativeKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestLoadCurationMissingFileIsEmpty(t *testing.T) {
	cfg := loadCuration(filepath.Join(t.TempDir(), "nope.json"))
	if _, ok := cfg.forCategory("movies"); ok {
		t.Fatalf("expected no rules for missing file")
	}
}

func TestLoadCurationParsesFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "curation.json")
	content := `{"categories":{"movies":{"blockIds":["x"],"blockTitleContains":["预告"],"pinIds":["y"]}}}`
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	cfg := loadCuration(path)
	cur, ok := cfg.forCategory("movies")
	if !ok {
		t.Fatalf("expected movies curation")
	}
	if _, blocked := cur.BlockIDs["x"]; !blocked {
		t.Fatalf("blockIds not parsed")
	}
	if len(cur.BlockTitleContains) != 1 || cur.BlockTitleContains[0] != "预告" {
		t.Fatalf("blockTitleContains not parsed: %+v", cur.BlockTitleContains)
	}
	if len(cur.PinIDs) != 1 || cur.PinIDs[0] != "y" {
		t.Fatalf("pinIds not parsed: %+v", cur.PinIDs)
	}
}
