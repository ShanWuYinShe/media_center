package main

import (
	"database/sql"
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

func TestCatalogApprovedPage(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1aaa", "A", 3600)
	mustUpsert(t, c, "bilibili", "BV1bbb", "B", 3600)
	mustUpsert(t, c, "bilibili", "BV1ccc", "C", 3600)
	mustUpsert(t, c, "bilibili", "BV1ddd", "D", 3600)

	add := func(nativeID string, score int) {
		t.Helper()
		if _, err := c.AddPendingMember("movies", "bilibili", nativeID, score); err != nil {
			t.Fatalf("add %s: %v", nativeID, err)
		}
	}
	add("BV1aaa", 20)
	add("BV1bbb", 80)
	add("BV1ccc", 50)
	add("BV1ddd", 10)
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1aaa", "approved", "t"); err != nil {
		t.Fatalf("approve aaa: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1bbb", "approved", "t"); err != nil {
		t.Fatalf("approve bbb: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1ccc", "approved", "t"); err != nil {
		t.Fatalf("approve ccc: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1ddd", "approved", "t"); err != nil {
		t.Fatalf("approve ddd: %v", err)
	}
	pinned := true
	if _, err := c.ReviewMember("movies", "bilibili", "BV1ccc", nil, &pinned, &sql.NullInt64{Int64: 0, Valid: true}, "t"); err != nil {
		t.Fatalf("pin/rank ccc: %v", err)
	}

	page1, total, err := c.ApprovedPage("movies", 1, 2)
	if err != nil {
		t.Fatalf("approved page1: %v", err)
	}
	if total != 4 {
		t.Fatalf("total = %d, want 4", total)
	}
	if len(page1) != 2 {
		t.Fatalf("page1 len = %d, want 2", len(page1))
	}
	if page1[0].NativeID != "BV1ccc" || page1[1].NativeID != "BV1bbb" {
		t.Fatalf("page1 order = %v", idsOf(page1))
	}

	page2, total, err := c.ApprovedPage("movies", 2, 2)
	if err != nil {
		t.Fatalf("approved page2: %v", err)
	}
	if total != 4 {
		t.Fatalf("page2 total = %d, want 4", total)
	}
	if len(page2) != 2 {
		t.Fatalf("page2 len = %d, want 2", len(page2))
	}
	if page2[0].NativeID != "BV1aaa" || page2[1].NativeID != "BV1ddd" {
		t.Fatalf("page2 order = %v", idsOf(page2))
	}

	page3, total, err := c.ApprovedPage("movies", 3, 2)
	if err != nil {
		t.Fatalf("approved page3: %v", err)
	}
	if total != 4 || len(page3) != 0 {
		t.Fatalf("page3 = total:%d len:%d, want total 4 len 0", total, len(page3))
	}
}

func TestCategoryVideosPageReadsCatalogDirectly(t *testing.T) {
	c := openTestCatalog(t)
	if err := c.UpsertVideo(CatalogVideo{
		Provider:     "bilibili",
		NativeID:     "BV1cat",
		Title:        "catalog movie",
		Description:  "desc",
		DurationSec:  3600,
		ThumbnailURL: "https://i0.hdslb.com/test.jpg",
	}); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if _, err := c.AddPendingMember("movies", "bilibili", "BV1cat", 50); err != nil {
		t.Fatalf("add: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1cat", "approved", "t"); err != nil {
		t.Fatalf("approve: %v", err)
	}

	s := &mediaServer{
		categoriesByID: map[string]ContentCategory{
			"movies": {ID: "movies", Title: "Movies", Description: "d"},
		},
		idCodec: NewIDCodec("test-secret"),
		catalog: c,
	}
	resp, err := s.categoryVideosPage(nil, s.categoriesByID["movies"], 1, 10)
	if err != nil {
		t.Fatalf("categoryVideosPage: %v", err)
	}
	if resp.Total != 1 || len(resp.Items) != 1 {
		t.Fatalf("unexpected response: total=%d items=%d", resp.Total, len(resp.Items))
	}
	if resp.Items[0].Title != "catalog movie" {
		t.Fatalf("title = %q", resp.Items[0].Title)
	}
	ref, err := s.idCodec.DecodeVideoRef(resp.Items[0].ID)
	if err != nil {
		t.Fatalf("decode id: %v", err)
	}
	if ref.Provider != "bilibili" || ref.ID != "BV1cat" {
		t.Fatalf("decoded ref = %s/%s", ref.Provider, ref.ID)
	}
	if resp.Items[0].ThumbnailHeaders["Referer"] == "" {
		t.Fatalf("expected bilibili thumbnail referer header")
	}
}

func idsOf(vs []CatalogVideo) []string {
	out := make([]string, len(vs))
	for i, v := range vs {
		out[i] = v.NativeID
	}
	return out
}

func TestCatalogSearchApprovedOnlyReturnsApproved(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1aaa", "流浪地球 科幻大片", 1800)
	mustUpsert(t, c, "bilibili", "BV1bbb", "Interstellar Movie", 1800)
	mustUpsert(t, c, "bilibili", "BV1ccc", "纪录片 地球脉动", 1800)
	mustUpsert(t, c, "bilibili", "BV1ddd", "未审核 地球", 1800)

	approve := func(cat, nid string) {
		if _, err := c.AddPendingMember(cat, "bilibili", nid, 10); err != nil {
			t.Fatalf("add %s/%s: %v", cat, nid, err)
		}
		if _, err := c.SetMemberStatus(cat, "bilibili", nid, "approved", "tester"); err != nil {
			t.Fatalf("approve %s/%s: %v", cat, nid, err)
		}
	}
	// aaa approved in two categories (must dedupe), bbb approved, ccc rejected, ddd pending.
	approve("movies", "BV1aaa")
	approve("tv", "BV1aaa")
	approve("movies", "BV1bbb")
	if _, err := c.AddPendingMember("movies", "bilibili", "BV1ccc", 10); err != nil {
		t.Fatalf("add ccc: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1ccc", "rejected", "tester"); err != nil {
		t.Fatalf("reject ccc: %v", err)
	}
	if _, err := c.AddPendingMember("movies", "bilibili", "BV1ddd", 10); err != nil {
		t.Fatalf("add ddd: %v", err)
	}

	// "地球" appears in aaa(approved), ccc(rejected), ddd(pending) → only aaa, deduped to one row.
	items, total, err := c.SearchApproved("地球", 1, 20)
	if err != nil {
		t.Fatalf("search 地球: %v", err)
	}
	if total != 1 || len(items) != 1 || items[0].NativeID != "BV1aaa" {
		t.Fatalf("search 地球: total=%d items=%v", total, idsOf(items))
	}

	// ASCII case-insensitive: lowercase query matches mixed-case title.
	items, total, err = c.SearchApproved("movie", 1, 20)
	if err != nil {
		t.Fatalf("search movie: %v", err)
	}
	if total != 1 || items[0].NativeID != "BV1bbb" {
		t.Fatalf("search movie: total=%d items=%v", total, idsOf(items))
	}

	// Creator field is searchable.
	if items, _, err = c.SearchApproved("up-BV1aaa", 1, 20); err != nil || len(items) != 1 || items[0].NativeID != "BV1aaa" {
		t.Fatalf("search creator: items=%v err=%v", idsOf(items), err)
	}

	// No match / empty query → empty result, no upstream call.
	if items, total, _ = c.SearchApproved("不存在的关键词", 1, 20); total != 0 || len(items) != 0 {
		t.Fatalf("search none: total=%d items=%v", total, idsOf(items))
	}
	if items, total, _ = c.SearchApproved("   ", 1, 20); total != 0 || len(items) != 0 {
		t.Fatalf("blank query: total=%d items=%v", total, idsOf(items))
	}

	// LIKE wildcards are escaped to literals: "%" must not match everything.
	if _, total, _ = c.SearchApproved("%", 1, 20); total != 0 {
		t.Fatalf("wildcard %% should be literal, got total=%d", total)
	}
}
