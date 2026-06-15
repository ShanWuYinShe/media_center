package main

import (
	"bufio"
	"bytes"
	"strings"
	"testing"
)

// addPending 把一条视频登记为某分类的 pending 候选(带分数),供 review 测试构造数据。
func addPending(t *testing.T, c *Catalog, category, nativeID string, score int) {
	t.Helper()
	if _, err := c.AddPendingMember(category, "bilibili", nativeID, score); err != nil {
		t.Fatalf("add pending %s: %v", nativeID, err)
	}
}

func idsOfPending(vs []PendingReviewItem) []string {
	out := make([]string, len(vs))
	for i, v := range vs {
		out[i] = v.NativeID
	}
	return out
}

func TestPendingReviewsOrderingAndFilter(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1a", "A", 1800)
	mustUpsert(t, c, "bilibili", "BV1b", "B", 1800)
	mustUpsert(t, c, "bilibili", "BV1c", "C", 1800)
	addPending(t, c, "movies", "BV1a", 5)
	addPending(t, c, "movies", "BV1b", 30)
	addPending(t, c, "tv", "BV1c", 30) // 不同分类

	// movies: 按 score DESC → BV1b(30), BV1a(5)
	got, err := c.PendingReviews(PendingReviewsOptions{CategoryID: "movies"})
	if err != nil {
		t.Fatalf("pending: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("len=%d want 2", len(got))
	}
	if got[0].NativeID != "BV1b" || got[1].NativeID != "BV1a" {
		t.Fatalf("order=%v want [BV1b BV1a]", idsOfPending(got))
	}
	if got[0].Score != 30 || got[0].CategoryID != "movies" {
		t.Fatalf("item0 fields wrong: score=%d category=%s", got[0].Score, got[0].CategoryID)
	}
	// 嵌入的 CatalogVideo 字段应被填充(标题 + creators)。
	if got[0].Title != "B" || len(got[0].Creators) == 0 || got[0].Creators[0] != "up-BV1b" {
		t.Fatalf("embedded CatalogVideo not populated: title=%q creators=%v", got[0].Title, got[0].Creators)
	}

	// 不限定分类:返回全部 pending。
	all, err := c.PendingReviews(PendingReviewsOptions{})
	if err != nil {
		t.Fatalf("all: %v", err)
	}
	if len(all) != 3 {
		t.Fatalf("all len=%d want 3", len(all))
	}

	// limit 取分数最高的一条。
	lim, _ := c.PendingReviews(PendingReviewsOptions{CategoryID: "movies", Limit: 1})
	if len(lim) != 1 || lim[0].NativeID != "BV1b" {
		t.Fatalf("limit result=%v", idsOfPending(lim))
	}
}

func TestPendingReviewsExcludesReviewed(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1ok", "ok", 1800)
	mustUpsert(t, c, "bilibili", "BV1no", "no", 1800)
	mustUpsert(t, c, "bilibili", "BV1pend", "pend", 1800)
	addPending(t, c, "movies", "BV1ok", 10)
	addPending(t, c, "movies", "BV1no", 10)
	addPending(t, c, "movies", "BV1pend", 10)
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1ok", "approved", "t"); err != nil {
		t.Fatalf("approve: %v", err)
	}
	if _, err := c.SetMemberStatus("movies", "bilibili", "BV1no", "rejected", "t"); err != nil {
		t.Fatalf("reject: %v", err)
	}

	got, _ := c.PendingReviews(PendingReviewsOptions{CategoryID: "movies"})
	if len(got) != 1 || got[0].NativeID != "BV1pend" {
		t.Fatalf("pending=%v, want only BV1pend (approved/rejected must be excluded)", idsOfPending(got))
	}
}

func TestReviewInteractiveReadsCommands(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1a", "A", 1800)
	mustUpsert(t, c, "bilibili", "BV1b", "B", 1800)
	mustUpsert(t, c, "bilibili", "BV1c", "C", 1800)
	mustUpsert(t, c, "bilibili", "BV1d", "D", 1800)
	addPending(t, c, "movies", "BV1a", 40)
	addPending(t, c, "movies", "BV1b", 30)
	addPending(t, c, "movies", "BV1c", 20)
	addPending(t, c, "movies", "BV1d", 10)

	items, _ := c.PendingReviews(PendingReviewsOptions{CategoryID: "movies"})
	// 顺序: BV1a(40), BV1b(30), BV1c(20), BV1d(10)
	r := bufio.NewScanner(strings.NewReader("a\ns\nr\nq\n"))
	r.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	var out bytes.Buffer
	approved, rejected, skipped := reviewInteractiveOn(r, &out, items, ReviewOptions{}, "tester", c)

	if approved != 1 || rejected != 1 || skipped != 1 {
		t.Fatalf("counts approved=%d rejected=%d skipped=%d, want 1/1/1", approved, rejected, skipped)
	}
	// BV1a→approved, BV1b→skip(仍 pending), BV1c→rejected, BV1d→quit 未处理。
	apv, _ := c.CountByStatus("movies", "approved")
	rej, _ := c.CountByStatus("movies", "rejected")
	pend, _ := c.CountByStatus("movies", "pending")
	if apv != 1 || rej != 1 || pend != 2 {
		t.Fatalf("db approved=%d rejected=%d pending=%d, want 1/1/2", apv, rej, pend)
	}
	got, _ := c.ApprovedForCategory("movies", 0)
	if len(got) != 1 || got[0].NativeID != "BV1a" {
		t.Fatalf("approved item=%v, want BV1a", idsOf(got))
	}
	// 卡片应打印候选标识与审校操作提示(便于人工核对与决策)。
	if !strings.Contains(out.String(), "BV1a") || !strings.Contains(out.String(), "[a]pprove") {
		t.Fatalf("review card missing id/prompt in output:\n%s", out.String())
	}
}
