package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func seedTestMember(t *testing.T, c *Catalog, cat, provider, nid, title string, score int) {
	t.Helper()
	if err := c.UpsertVideo(CatalogVideo{Provider: provider, NativeID: nid, Title: title, DurationSec: 1800}); err != nil {
		t.Fatalf("upsert %s: %v", nid, err)
	}
	if _, err := c.AddPendingMember(cat, provider, nid, score); err != nil {
		t.Fatalf("add %s: %v", nid, err)
	}
}

func TestCatalogListMembersFilters(t *testing.T) {
	c := openTestCatalog(t)
	seedTestMember(t, c, "movies", "bilibili", "BV1a", "A", 10)
	seedTestMember(t, c, "movies", "bilibili", "BV1b", "B", 30)
	seedTestMember(t, c, "movies", "bilibili", "BV1c", "C", 20)
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1b", "approved", "tester"); err != nil {
		t.Fatalf("approve: %v", err)
	}

	pending, err := c.ListMembers("movies", "pending", 0)
	if err != nil {
		t.Fatalf("list pending: %v", err)
	}
	if len(pending) != 2 {
		t.Fatalf("expected 2 pending, got %d", len(pending))
	}
	// pending order: score DESC -> BV1c(20), BV1a(10)
	if pending[0].NativeID != "BV1c" || pending[1].NativeID != "BV1a" {
		t.Fatalf("pending order = %s,%s", pending[0].NativeID, pending[1].NativeID)
	}
	if pending[0].Status != "pending" {
		t.Fatalf("expected status pending, got %q", pending[0].Status)
	}

	all, err := c.ListMembers("movies", "", 0)
	if err != nil {
		t.Fatalf("list all: %v", err)
	}
	if len(all) != 3 {
		t.Fatalf("expected 3 total, got %d", len(all))
	}
	// BV1b approved with score 30 still sorts first by score.
	if all[0].NativeID != "BV1b" || all[0].Status != "approved" {
		t.Fatalf("expected BV1b approved first, got %s/%s", all[0].NativeID, all[0].Status)
	}

	// invalid status filter is rejected.
	if _, err := c.ListMembers("movies", "bogus", 0); err == nil {
		t.Fatalf("expected error for invalid status filter")
	}
}

func TestCatalogReviewMember(t *testing.T) {
	c := openTestCatalog(t)
	seedTestMember(t, c, "movies", "bilibili", "BV1a", "A", 10)
	seedTestMember(t, c, "movies", "bilibili", "BV1b", "B", 5)

	approved := "approved"
	pinned := true
	if _, err := c.ReviewMember("movies", "bilibili", "BV1b", &approved, &pinned, &sql.NullInt64{Int64: 0, Valid: true}, "admin"); err != nil {
		t.Fatalf("review b: %v", err)
	}
	if _, err := c.ReviewMember("movies", "bilibili", "BV1a", &approved, nil, nil, "admin"); err != nil {
		t.Fatalf("review a: %v", err)
	}

	got, err := c.ApprovedForCategory("movies", 0)
	if err != nil {
		t.Fatalf("approved: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("expected 2 approved, got %d", len(got))
	}
	// BV1b is pinned -> first even though it has a lower score than BV1a.
	if got[0].NativeID != "BV1b" {
		t.Fatalf("expected pinned BV1b first, got %s", got[0].NativeID)
	}

	// Empty update (no status/pinned/rank) is an error.
	if _, err := c.ReviewMember("movies", "bilibili", "BV1a", nil, nil, nil, "admin"); err == nil {
		t.Fatalf("expected error for empty review")
	}
	// Invalid status is an error.
	bogus := "bogus"
	if _, err := c.ReviewMember("movies", "bilibili", "BV1a", &bogus, nil, nil, "admin"); err == nil {
		t.Fatalf("expected error for invalid status")
	}
	// Missing member affects 0 rows (not an error).
	n, err := c.ReviewMember("movies", "bilibili", "BVmissing", &approved, nil, nil, "admin")
	if err != nil {
		t.Fatalf("review missing: %v", err)
	}
	if n != 0 {
		t.Fatalf("expected 0 affected for missing member, got %d", n)
	}

	// Clearing rank works.
	if _, err := c.ReviewMember("movies", "bilibili", "BV1b", nil, nil, &sql.NullInt64{Valid: false}, "admin"); err != nil {
		t.Fatalf("clear rank: %v", err)
	}
	items, err := c.ListMembers("movies", "approved", 0)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	for _, it := range items {
		if it.NativeID == "BV1b" && it.Rank.Valid {
			t.Fatalf("expected rank cleared for BV1b")
		}
	}
}

func TestCatalogOverviewCounts(t *testing.T) {
	c := openTestCatalog(t)
	seedTestMember(t, c, "movies", "bilibili", "BV1a", "A", 10)
	seedTestMember(t, c, "movies", "bilibili", "BV1b", "B", 10)
	seedTestMember(t, c, "tv", "bilibili", "BV1c", "C", 10)
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1a", "approved", "x"); err != nil {
		t.Fatalf("approve: %v", err)
	}
	if _, err := c.SetMemberStatus("tv", "bilibili", "BV1c", "rejected", "x"); err != nil {
		t.Fatalf("reject: %v", err)
	}
	counts, err := c.OverviewCounts()
	if err != nil {
		t.Fatalf("overview: %v", err)
	}
	if counts["movies"].Approved != 1 || counts["movies"].Pending != 1 {
		t.Fatalf("movies counts wrong: %+v", counts["movies"])
	}
	if counts["tv"].Rejected != 1 || counts["tv"].Pending != 0 {
		t.Fatalf("tv counts wrong: %+v", counts["tv"])
	}
}

func TestOpTracker(t *testing.T) {
	var tr opTracker
	if !tr.tryStart(opSeed, "movies") {
		t.Fatal("first start should succeed")
	}
	if tr.tryStart(opRebuild, "all") {
		t.Fatal("second start should fail while running")
	}
	st := tr.snapshot()
	if !st.Running || st.Kind != "seed" || st.Category != "movies" {
		t.Fatalf("unexpected running state: %+v", st)
	}
	tr.finish("done", nil)
	st = tr.snapshot()
	if st.Running || st.Message != "done" || st.Error != "" {
		t.Fatalf("unexpected finished state: %+v", st)
	}
	if !tr.tryStart(opRebuild, "all") {
		t.Fatal("start after finish should succeed")
	}
}

func newInternalTestServer(t *testing.T, token string) (*mediaServer, *Catalog) {
	t.Helper()
	c := openTestCatalog(t)
	cats := []ContentCategory{{ID: "movies", Title: "Movies"}, {ID: "tv", Title: "TV"}}
	store, err := NewSnapshotStore("")
	if err != nil {
		t.Fatalf("snapshot store: %v", err)
	}
	s := &mediaServer{
		config:         AppConfig{InternalToken: token, SnapshotTargetSize: 60, SnapshotEnabled: true},
		categories:     cats,
		categoriesByID: indexCategories(cats),
		idCodec:        NewIDCodec("test-secret"),
		snapshots:      store,
		catalog:        c,
		ops:            &opTracker{},
	}
	return s, c
}

func doInternal(s *mediaServer, method, path, token string, body any) *httptest.ResponseRecorder {
	var r *http.Request
	if body != nil {
		buf, _ := json.Marshal(body)
		r = httptest.NewRequest(method, path, bytes.NewReader(buf))
	} else {
		r = httptest.NewRequest(method, path, nil)
	}
	if token != "" {
		r.Header.Set("X-Internal-Token", token)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}

func TestInternalTokenGuard(t *testing.T) {
	s, _ := newInternalTestServer(t, "secret")
	if w := doInternal(s, http.MethodGet, "/internal/catalog/overview", "", nil); w.Code != http.StatusUnauthorized {
		t.Fatalf("no token: want 401, got %d", w.Code)
	}
	if w := doInternal(s, http.MethodGet, "/internal/catalog/overview", "wrong", nil); w.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token: want 401, got %d", w.Code)
	}
	if w := doInternal(s, http.MethodGet, "/internal/catalog/overview", "secret", nil); w.Code != http.StatusOK {
		t.Fatalf("good token: want 200, got %d", w.Code)
	}
}

func TestInternalCatalogImportCreatesPendingOnly(t *testing.T) {
	s, c := newInternalTestServer(t, "secret")
	w := doInternal(s, http.MethodPost, "/internal/catalog/import", "secret", map[string]any{
		"items": []map[string]any{
			{
				"categoryId": "movies",
				"provider":   "bilibili",
				"nativeId":   "BV1new",
				"title":      "经典电影 完整版 正片",
				"score":      42,
				"sourceUrl":  "https://www.bilibili.com/video/BV1new",
			},
		},
	})
	if w.Code != http.StatusOK {
		t.Fatalf("import status = %d, body=%s", w.Code, w.Body.String())
	}
	items, err := c.ListMembers("movies", "pending", 0)
	if err != nil {
		t.Fatalf("list pending: %v", err)
	}
	if len(items) != 1 || items[0].NativeID != "BV1new" || items[0].Status != "pending" || items[0].Score != 42 {
		t.Fatalf("unexpected pending import: %+v", items)
	}

	approved := "approved"
	if _, err := c.ReviewMember("movies", "bilibili", "BV1new", &approved, nil, nil, "tester"); err != nil {
		t.Fatalf("approve imported: %v", err)
	}
	w = doInternal(s, http.MethodPost, "/internal/catalog/import", "secret", map[string]any{
		"items": []map[string]any{
			{"categoryId": "movies", "provider": "bilibili", "nativeId": "BV1new", "title": "标题刷新", "score": 1},
		},
	})
	if w.Code != http.StatusOK {
		t.Fatalf("reimport status = %d, body=%s", w.Code, w.Body.String())
	}
	approvedItems, err := c.ListMembers("movies", "approved", 0)
	if err != nil {
		t.Fatalf("list approved: %v", err)
	}
	if len(approvedItems) != 1 || approvedItems[0].NativeID != "BV1new" {
		t.Fatalf("approved decision should be preserved, got %+v", approvedItems)
	}
}

func TestInternalDisabledWhenNoToken(t *testing.T) {
	s, _ := newInternalTestServer(t, "")
	// Even with a header, an unset token disables the whole /internal tree (404).
	if w := doInternal(s, http.MethodGet, "/internal/catalog/overview", "anything", nil); w.Code != http.StatusNotFound {
		t.Fatalf("disabled: want 404, got %d", w.Code)
	}
}

func TestInternalReviewFlow(t *testing.T) {
	s, c := newInternalTestServer(t, "secret")
	seedTestMember(t, c, "movies", "bilibili", "BV1a", "A", 10)

	w := doInternal(s, http.MethodGet, "/internal/catalog/items?category=movies&status=pending", "secret", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("items: %d (%s)", w.Code, w.Body.String())
	}
	var listResp struct {
		Items []internalReviewItem `json:"items"`
		Total int                  `json:"total"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &listResp); err != nil {
		t.Fatalf("decode items: %v", err)
	}
	if listResp.Total != 1 || listResp.Items[0].NativeID != "BV1a" {
		t.Fatalf("unexpected items: %+v", listResp)
	}

	approved := "approved"
	w = doInternal(s, http.MethodPost, "/internal/catalog/review", "secret", internalReviewRequest{
		CategoryID: "movies", Provider: "bilibili", NativeID: "BV1a", Status: &approved,
	})
	if w.Code != http.StatusOK {
		t.Fatalf("review: %d (%s)", w.Code, w.Body.String())
	}

	w = doInternal(s, http.MethodGet, "/internal/catalog/overview", "secret", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("overview: %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), "\"approved\":1") {
		t.Fatalf("overview missing approved count: %s", w.Body.String())
	}

	// Reviewing a missing member returns 404.
	w = doInternal(s, http.MethodPost, "/internal/catalog/review", "secret", internalReviewRequest{
		CategoryID: "movies", Provider: "bilibili", NativeID: "BVmissing", Status: &approved,
	})
	if w.Code != http.StatusNotFound {
		t.Fatalf("review missing: want 404, got %d", w.Code)
	}
}
