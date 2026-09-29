package main

import (
	"fmt"
	"testing"
)

// ImportPending 分批提交语义:跨批计数一致、坏输入零写入、重复导入幂等、
// 分P聚合不受分批影响。

func importItemsOf(n int, status string) []CatalogImportItem {
	items := make([]CatalogImportItem, 0, n)
	for i := 0; i < n; i++ {
		items = append(items, CatalogImportItem{
			CategoryID: "movies",
			Status:     status,
			Score:      10,
			Video: CatalogVideo{
				Provider: "bilibili",
				NativeID: fmt.Sprintf("BV1batch%04d", i),
				Title:    fmt.Sprintf("批量导入 %04d", i),
			},
		})
	}
	return items
}

func TestImportPendingBatchedCounts(t *testing.T) {
	c := openTestCatalog(t)
	// 跨批规模(importBatchSize=200):450 条 → 3 个事务,计数必须与单事务一致
	videos, imported, approved, skipped, err := c.ImportPending(importItemsOf(450, "approved"), "tester")
	if err != nil {
		t.Fatalf("import: %v", err)
	}
	if videos != 450 || imported != 450 || approved != 450 || skipped != 0 {
		t.Fatalf("counts = (%d,%d,%d,%d), want (450,450,450,0)", videos, imported, approved, skipped)
	}
	if n, err := c.CountByStatus("movies", "approved"); err != nil || n != 450 {
		t.Fatalf("approved in db = %d err %v, want 450", n, err)
	}
	// 重复导入幂等:members 全 skipped,元数据照常刷新
	videos2, imported2, approved2, skipped2, err := c.ImportPending(importItemsOf(450, "approved"), "tester")
	if err != nil {
		t.Fatalf("re-import: %v", err)
	}
	if videos2 != 450 || imported2 != 0 || approved2 != 0 || skipped2 != 450 {
		t.Fatalf("re-import counts = (%d,%d,%d,%d), want (450,0,0,450)", videos2, imported2, approved2, skipped2)
	}
}

func TestImportPendingInvalidWritesNothing(t *testing.T) {
	c := openTestCatalog(t)
	// 校验全部前置:任何一条非法(哪怕在最后一批)直接报错,一条不写——
	// 与旧"单事务全回滚"对坏输入的可观察行为一致。
	items := importItemsOf(250, "approved")
	items = append(items, CatalogImportItem{
		CategoryID: "movies",
		Status:     "bogus",
		Video:      CatalogVideo{Provider: "bilibili", NativeID: "BV1bad", Title: "坏状态"},
	})
	if _, _, _, _, err := c.ImportPending(items, "tester"); err == nil {
		t.Fatal("invalid status must fail the import")
	}
	if n, err := c.CountByStatus("movies", "approved"); err != nil || n != 0 {
		t.Fatalf("approved after failed import = %d err %v, want 0", n, err)
	}
	providers, err := c.DistinctProviders()
	if err != nil || len(providers) != 0 {
		t.Fatalf("providers after failed import = %v err %v, want empty", providers, err)
	}
}

func TestImportPendingAggregatesPagesAcrossBatches(t *testing.T) {
	c := openTestCatalog(t)
	// 分P聚合发生在分批之前,同 base 的页条目聚成一条,与事务边界无关
	items := make([]CatalogImportItem, 0, 5)
	for p := 1; p <= 5; p++ {
		items = append(items, CatalogImportItem{
			CategoryID: "movies",
			Status:     "approved",
			Video: CatalogVideo{
				Provider:    "bilibili",
				NativeID:    fmt.Sprintf("BV1page_p%d", p),
				Title:       "多P视频",
				DurationSec: 60,
			},
		})
	}
	videos, imported, approved, skipped, err := c.ImportPending(items, "tester")
	if err != nil {
		t.Fatalf("import pages: %v", err)
	}
	if videos != 1 || imported != 1 || approved != 1 || skipped != 0 {
		t.Fatalf("page aggregate counts = (%d,%d,%d,%d), want (1,1,1,0)", videos, imported, approved, skipped)
	}
}
