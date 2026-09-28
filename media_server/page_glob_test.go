package main

import (
	"testing"
)

// TestPageRecordFilteringBySuffixGlob 锁定分P过滤的精确语义:只有以
// _p<纯数字> 结尾的页记录被排除,任何位置含 "_p" 的合法 ID(如 YouTube
// 的 11 字符 ID)必须照常出现在全部 approved 公开查询里。
//
// 历史实现的两个缺陷:
//  1. LIKE '%\_p%' 子串匹配把 ab_pdefX9Zk 这类 ID 从 providers/categories/
//     videos/search 静默隐藏(能入库、能 resolve 播放,却不可见);
//  2. ApprovedPage/ApprovedForCategory 完全不过滤,与 provider 维度口径不一。
func TestPageRecordFilteringBySuffixGlob(t *testing.T) {
	c := openTestCatalog(t)
	fixtures := []struct{ provider, id, title string }{
		{"youtube", "ab_pdefX9Zk", "合法YouTubeID中间含_p"},
		{"youtube", "dQw4w9WgXcQ", "正常YouTubeID"},
		{"bilibili", "BV1base", "正常基础视频"},
		{"bilibili", "BV1base_p2", "分P第2页"},
		{"bilibili", "BV1base_p12", "分P第12页"},
		{"bilibili", "BV1base_p123", "分P第123页"},
	}
	for _, f := range fixtures {
		mustUpsert(t, c, f.provider, f.id, f.title, 100)
		if _, err := c.AddPendingMember("movies", f.provider, f.id, 10); err != nil {
			t.Fatalf("add member %s: %v", f.id, err)
		}
		if _, err := c.SetMemberStatus("movies", f.provider, f.id, "approved", "tester"); err != nil {
			t.Fatalf("approve %s: %v", f.id, err)
		}
	}
	allVisible := map[string]bool{"ab_pdefX9Zk": true, "dQw4w9WgXcQ": true, "BV1base": true}

	// expected 是该查询维度下应可见的 ID 集合;页记录(_p<数字> 结尾)一律不得出现。
	assertVisibleOnly := func(name string, items []CatalogVideo, total int, expected map[string]bool) {
		t.Helper()
		got := map[string]bool{}
		for _, v := range items {
			got[v.NativeID] = true
		}
		for id := range expected {
			if !got[id] {
				t.Fatalf("%s: 合法 ID %s 不可见(got %v)", name, id, got)
			}
		}
		for id := range got {
			if !expected[id] {
				t.Fatalf("%s: 页记录 %s 不应公开可见", name, id)
			}
		}
		if total != 0 && total != len(expected) {
			t.Fatalf("%s: total = %d, want %d", name, total, len(expected))
		}
	}

	page, total, err := c.ApprovedPage("movies", 1, 40)
	if err != nil {
		t.Fatalf("ApprovedPage: %v", err)
	}
	assertVisibleOnly("ApprovedPage", page, total, allVisible)

	forCategory, err := c.ApprovedForCategory("movies", 0)
	if err != nil {
		t.Fatalf("ApprovedForCategory: %v", err)
	}
	assertVisibleOnly("ApprovedForCategory", forCategory, len(forCategory), allVisible)

	// provider 维度:youtube 两条都可见(历史子串过滤下 ab_pdefX9Zk 会消失),
	// bilibili 只剩基础视频。
	ytPage, ytTotal, err := c.ApprovedPageForProvider("youtube", "movies", 1, 40)
	if err != nil {
		t.Fatalf("ApprovedPageForProvider youtube: %v", err)
	}
	assertVisibleOnly("ApprovedPageForProvider(youtube)", ytPage, ytTotal,
		map[string]bool{"ab_pdefX9Zk": true, "dQw4w9WgXcQ": true})

	biliPage, biliTotal, err := c.ApprovedPageForProvider("bilibili", "movies", 1, 40)
	if err != nil {
		t.Fatalf("ApprovedPageForProvider bilibili: %v", err)
	}
	if len(biliPage) != 1 || biliPage[0].NativeID != "BV1base" || biliTotal != 1 {
		t.Fatalf("bilibili page = %v total %d, want [BV1base] 1", biliPage, biliTotal)
	}

	// 搜索同样遵循精确语义。
	found, foundTotal, err := c.SearchApproved("合法", "", 1, 20)
	if err != nil {
		t.Fatalf("SearchApproved: %v", err)
	}
	assertVisibleOnly("SearchApproved", found, foundTotal,
		map[string]bool{"ab_pdefX9Zk": true})

	// 分类/provider 维度辅助查询:页记录不吞掉分类与 provider。
	cats, err := c.CategoriesForProvider("bilibili")
	if err != nil || len(cats) != 1 || cats[0] != "movies" {
		t.Fatalf("CategoriesForProvider = %v err %v, want [movies]", cats, err)
	}
	providers, err := c.DistinctProviders()
	if err != nil || len(providers) != 2 {
		t.Fatalf("DistinctProviders = %v err %v, want 2 providers", providers, err)
	}

	// 审核统计口径:页记录不计入 approved 数。
	if n, err := c.CountByStatus("movies", "approved"); err != nil || n != 3 {
		t.Fatalf("CountByStatus approved = %d err %v, want 3", n, err)
	}

	// ConsolidatePages:按同一精确定义选中 3 条页记录并并入基础视频。
	groups, deleted, created, err := c.ConsolidatePages()
	if err != nil {
		t.Fatalf("ConsolidatePages: %v", err)
	}
	if groups != 1 || deleted != 3 || created != 0 {
		t.Fatalf("ConsolidatePages = (%d groups, %d deleted, %d created), want (1, 3, 0)", groups, deleted, created)
	}
	// 合并后合法 ID 不受影响。
	after, err := c.ApprovedForCategory("movies", 0)
	if err != nil {
		t.Fatalf("approved after consolidate: %v", err)
	}
	assertVisibleOnly("after-consolidate", after, len(after), allVisible)
}
