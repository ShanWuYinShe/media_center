package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSnapshotStoreRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store, err := NewSnapshotStore(dir)
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	snap := CategorySnapshot{
		CategoryID: "movies",
		UpdatedAt:  time.Now().UTC().Truncate(time.Second),
		Items: []VideoSummary{
			{ID: "a", Title: "Film A", PlaybackKind: "vod"},
			{ID: "b", Title: "Film B", PlaybackKind: "vod"},
		},
	}
	if err := store.Set(snap); err != nil {
		t.Fatalf("set: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "movies.json")); err != nil {
		t.Fatalf("snapshot file not written: %v", err)
	}
	// A fresh store must rehydrate from disk (survives restart).
	reloaded, err := NewSnapshotStore(dir)
	if err != nil {
		t.Fatalf("reload store: %v", err)
	}
	got, ok := reloaded.Get("movies")
	if !ok {
		t.Fatalf("expected movies snapshot after reload")
	}
	if len(got.Items) != 2 || got.Items[0].ID != "a" {
		t.Fatalf("reloaded snapshot mismatch: %+v", got.Items)
	}
}

func TestPaginateSnapshotCopiesAndPaginates(t *testing.T) {
	cat := ContentCategory{ID: "movies", Title: "电影", Description: "d"}
	snap := CategorySnapshot{CategoryID: "movies"}
	for i := 0; i < 5; i++ {
		snap.Items = append(snap.Items, VideoSummary{ID: fmt.Sprintf("v%d", i), ThumbnailURL: "raw"})
	}
	resp := paginateSnapshot(cat, snap, 2, 2)
	if resp.Total != 5 {
		t.Fatalf("total = %d, want 5", resp.Total)
	}
	if len(resp.Items) != 2 || resp.Items[0].ID != "v2" {
		t.Fatalf("page 2 mismatch: %+v", resp.Items)
	}
	// Mutating returned items (as delivery rewriting does) must not touch the
	// shared, stored snapshot.
	resp.Items[0].ThumbnailURL = "rewritten"
	if snap.Items[2].ThumbnailURL != "raw" {
		t.Fatalf("paginateSnapshot must copy items; stored snapshot was mutated")
	}
}

func TestPaginateSnapshotBeyondEnd(t *testing.T) {
	cat := ContentCategory{ID: "movies", Title: "电影", Description: "d"}
	snap := CategorySnapshot{CategoryID: "movies", Items: []VideoSummary{{ID: "a"}, {ID: "b"}}}
	resp := paginateSnapshot(cat, snap, 5, 10)
	if len(resp.Items) != 0 {
		t.Fatalf("expected empty page beyond end, got %d", len(resp.Items))
	}
	if resp.Total != 2 {
		t.Fatalf("total = %d, want 2", resp.Total)
	}
}

func TestBuildCategorySnapshotAppliesTarget(t *testing.T) {
	fp := &queryAwareProvider{id: "fake", itemsPerPage: 6}
	s := &mediaServer{
		config:         AppConfig{MaxCategoryPages: 3, SnapshotTargetSize: 10},
		providers:      map[string]VideoProvider{"fake": fp},
		enabledSources: []string{"fake"},
		idCodec:        NewIDCodec("test-secret"),
		curation:       CurationConfig{categories: map[string]CategoryCuration{}},
	}
	cat := ContentCategory{
		ID: "general", Title: "General", Query: "dota a",
		Queries: []string{"dota a", "dota b", "dota c"}, Keywords: []string{"dota"},
	}
	snap, err := s.buildCategorySnapshot(context.Background(), cat)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if snap.CategoryID != "general" {
		t.Fatalf("categoryID = %q", snap.CategoryID)
	}
	if len(snap.Items) != 10 {
		t.Fatalf("expected capped to target 10, got %d", len(snap.Items))
	}
	if snap.UpdatedAt.IsZero() {
		t.Fatalf("UpdatedAt not set")
	}
}

func TestCategoryVideosPagePrefersSnapshotThenFallsBack(t *testing.T) {
	dir := t.TempDir()
	store, err := NewSnapshotStore(dir)
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	fp := &queryAwareProvider{id: "fake", itemsPerPage: 4}
	s := &mediaServer{
		config:         AppConfig{MaxCategoryPages: 2},
		providers:      map[string]VideoProvider{"fake": fp},
		enabledSources: []string{"fake"},
		idCodec:        NewIDCodec("test-secret"),
		snapshots:      store,
	}
	cat := ContentCategory{
		ID: "general", Title: "综合", Description: "d",
		Query: "dota a", Queries: []string{"dota a"}, Keywords: []string{"dota"},
	}

	// No snapshot yet → live fallback still returns items (no empty page).
	live, err := s.categoryVideosPage(context.Background(), cat, 1, 5)
	if err != nil {
		t.Fatalf("live fallback: %v", err)
	}
	if len(live.Items) == 0 {
		t.Fatalf("expected live fallback items when snapshot absent")
	}

	// Once a snapshot exists, it is served verbatim (sentinel proves source).
	if err := store.Set(CategorySnapshot{
		CategoryID: "general",
		UpdatedAt:  time.Now(),
		Items:      []VideoSummary{{ID: "snapshot-sentinel", Title: "S"}},
	}); err != nil {
		t.Fatalf("set snapshot: %v", err)
	}
	snapResp, err := s.categoryVideosPage(context.Background(), cat, 1, 5)
	if err != nil {
		t.Fatalf("snapshot serve: %v", err)
	}
	if len(snapResp.Items) != 1 || snapResp.Items[0].ID != "snapshot-sentinel" {
		t.Fatalf("expected snapshot to be served, got %+v", snapResp.Items)
	}
}
