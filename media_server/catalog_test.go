package main

import (
	"context"
	"path/filepath"
	"testing"
)

func openTestCatalog(t *testing.T) *Catalog {
	t.Helper()
	c, err := OpenCatalog(filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatalf("open catalog: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func mustUpsert(t *testing.T, c *Catalog, provider, nativeID, title string, dur int64) {
	t.Helper()
	if err := c.UpsertVideo(CatalogVideo{
		Provider: provider, NativeID: nativeID, Title: title, DurationSec: dur,
		Creators: []string{"up-" + nativeID},
	}); err != nil {
		t.Fatalf("upsert %s: %v", nativeID, err)
	}
}

func TestCatalogApprovedOrderingByScore(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1aaa", "A", 1800)
	mustUpsert(t, c, "bilibili", "BV1bbb", "B", 1800)
	mustUpsert(t, c, "bilibili", "BV1ccc", "C", 1800)

	// Add as pending with different scores, approve all three.
	for nid, score := range map[string]int{"BV1aaa": 5, "BV1bbb": 30, "BV1ccc": 30} {
		if _, err := c.AddPendingMember("movies", "bilibili", nid, score); err != nil {
			t.Fatalf("add %s: %v", nid, err)
		}
		if _, err := c.SetMemberStatus("movies", "bilibili", nid, "approved", "tester"); err != nil {
			t.Fatalf("approve %s: %v", nid, err)
		}
	}

	got, err := c.ApprovedForCategory("movies", 0)
	if err != nil {
		t.Fatalf("approved: %v", err)
	}
	if len(got) != 3 {
		t.Fatalf("expected 3 approved, got %d", len(got))
	}
	// score DESC, then native_id ASC → bbb(30), ccc(30), aaa(5).
	wantOrder := []string{"BV1bbb", "BV1ccc", "BV1aaa"}
	for i, w := range wantOrder {
		if got[i].NativeID != w {
			t.Fatalf("order[%d] = %s, want %s (full: %v)", i, got[i].NativeID, w, idsOf(got))
		}
	}
	if got[0].Creators == nil || got[0].Creators[0] != "up-BV1bbb" {
		t.Fatalf("creators not round-tripped: %v", got[0].Creators)
	}
}

func TestCatalogApprovedOnlyReturnsApproved(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1ok", "ok", 1800)
	mustUpsert(t, c, "bilibili", "BV1no", "no", 1800)
	mustUpsert(t, c, "bilibili", "BV1pend", "pend", 1800)

	_, _ = c.AddPendingMember("movies", "bilibili", "BV1ok", 10)
	_, _ = c.AddPendingMember("movies", "bilibili", "BV1no", 10)
	_, _ = c.AddPendingMember("movies", "bilibili", "BV1pend", 10)
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1ok", "approved", "t"); err != nil {
		t.Fatalf("approve: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1no", "rejected", "t"); err != nil {
		t.Fatalf("reject: %v", err)
	}

	got, err := c.ApprovedForCategory("movies", 0)
	if err != nil {
		t.Fatalf("approved: %v", err)
	}
	if len(got) != 1 || got[0].NativeID != "BV1ok" {
		t.Fatalf("expected only BV1ok approved, got %v", idsOf(got))
	}

	pending, _ := c.CountByStatus("movies", "pending")
	approved, _ := c.CountByStatus("movies", "approved")
	rejected, _ := c.CountByStatus("movies", "rejected")
	if pending != 1 || approved != 1 || rejected != 1 {
		t.Fatalf("counts pending=%d approved=%d rejected=%d", pending, approved, rejected)
	}
}

func TestCatalogAddPendingPreservesDecision(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1x", "x", 1800)

	isNew, err := c.AddPendingMember("movies", "bilibili", "BV1x", 5)
	if err != nil || !isNew {
		t.Fatalf("first add: isNew=%v err=%v", isNew, err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1x", "approved", "t"); err != nil {
		t.Fatalf("approve: %v", err)
	}
	// Re-seeding must NOT reset an existing review decision.
	isNew, err = c.AddPendingMember("movies", "bilibili", "BV1x", 99)
	if err != nil {
		t.Fatalf("re-add: %v", err)
	}
	if isNew {
		t.Fatalf("re-add should report not-new")
	}
	approved, _ := c.CountByStatus("movies", "approved")
	pending, _ := c.CountByStatus("movies", "pending")
	if approved != 1 || pending != 0 {
		t.Fatalf("decision not preserved: approved=%d pending=%d", approved, pending)
	}
}

func TestCatalogRejectsInvalidStatus(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1x", "x", 1800)
	_, _ = c.AddPendingMember("movies", "bilibili", "BV1x", 5)
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1x", "bogus", "t"); err == nil {
		t.Fatalf("expected invalid status to error")
	}
}

func TestBuildCategorySnapshotPrefersCatalog(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1cat", "catalog movie", 3600)
	if _, err := c.AddPendingMember("movies", "bilibili", "BV1cat", 50); err != nil {
		t.Fatalf("add: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1cat", "approved", "t"); err != nil {
		t.Fatalf("approve: %v", err)
	}

	// A live provider that would return different (sentinel) items; the catalog
	// path must win, so none of these should appear.
	fp := &queryAwareProvider{id: "fake", itemsPerPage: 6}
	s := &mediaServer{
		config:         AppConfig{MaxCategoryPages: 3, SnapshotTargetSize: 10},
		providers:      map[string]VideoProvider{"fake": fp},
		enabledSources: []string{"fake"},
		idCodec:        NewIDCodec("test-secret"),
		curation:       CurationConfig{categories: map[string]CategoryCuration{}},
		catalog:        c,
	}
	cat := ContentCategory{
		ID: "movies", Title: "Movies", Query: "dota a",
		Queries: []string{"dota a"}, Keywords: []string{"dota"},
	}
	snap, err := s.buildCategorySnapshot(context.Background(), cat)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if len(snap.Items) != 1 {
		t.Fatalf("expected 1 catalog item, got %d (%v)", len(snap.Items), idSummaries(snap.Items))
	}
	if snap.Items[0].Title != "catalog movie" {
		t.Fatalf("expected catalog item, got %q", snap.Items[0].Title)
	}
	// The encoded ID must decode back to the stable native ref.
	ref, err := s.idCodec.DecodeVideoRef(snap.Items[0].ID)
	if err != nil {
		t.Fatalf("decode id: %v", err)
	}
	if ref.Provider != "bilibili" || ref.ID != "BV1cat" {
		t.Fatalf("decoded ref = %s/%s, want bilibili/BV1cat", ref.Provider, ref.ID)
	}
	if snap.Items[0].ThumbnailHeaders["Referer"] == "" {
		t.Fatalf("expected bilibili thumbnail Referer header")
	}
}

func TestBuildCategorySnapshotFallsBackWhenCatalogEmpty(t *testing.T) {
	c := openTestCatalog(t) // no approved rows for this category
	fp := &queryAwareProvider{id: "fake", itemsPerPage: 6}
	s := &mediaServer{
		config:         AppConfig{MaxCategoryPages: 3, SnapshotTargetSize: 10},
		providers:      map[string]VideoProvider{"fake": fp},
		enabledSources: []string{"fake"},
		idCodec:        NewIDCodec("test-secret"),
		curation:       CurationConfig{categories: map[string]CategoryCuration{}},
		catalog:        c,
	}
	cat := ContentCategory{
		ID: "general", Title: "General", Query: "dota a",
		Queries: []string{"dota a", "dota b"}, Keywords: []string{"dota"},
	}
	snap, err := s.buildCategorySnapshot(context.Background(), cat)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if len(snap.Items) == 0 {
		t.Fatalf("expected live-fallback items when catalog empty")
	}
	// Live items come from the fake provider (titles contain the query term).
	if snap.Items[0].Title == "catalog movie" {
		t.Fatalf("unexpected catalog item in fallback path")
	}
}

func idsOf(vs []CatalogVideo) []string {
	out := make([]string, len(vs))
	for i, v := range vs {
		out[i] = v.NativeID
	}
	return out
}

func idSummaries(vs []VideoSummary) []string {
	out := make([]string, len(vs))
	for i, v := range vs {
		out[i] = v.Title
	}
	return out
}
