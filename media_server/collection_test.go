package main

import (
	"testing"
)

func TestReplaceCollectionsAndApprovedQueue(t *testing.T) {
	c := openTestCatalog(t)
	for _, id := range []string{"BV1ep1", "BV1ep2", "BV1ep3", "BV1ep4"} {
		mustUpsert(t, c, "bilibili", id, id, 600)
	}
	// ep1/ep3/ep4 已审核,ep2 仍 pending:队列必须跳过未过审剧集。
	for _, id := range []string{"BV1ep1", "BV1ep3", "BV1ep4"} {
		approveTo(t, c, "movies", "bilibili", id)
	}
	if _, err := c.AddPendingMember("movies", "bilibili", "BV1ep2", 5); err != nil {
		t.Fatalf("add pending: %v", err)
	}

	collections, members, err := c.ReplaceCollections("bilibili", []CollectionImport{
		{ID: "season-1", Title: "测试合集", VideoIDs: []string{"BV1ep1", "BV1ep2", "BV1ep3", "BV1ep3", " ", "BV1ep4"}},
	})
	if err != nil {
		t.Fatalf("replace collections: %v", err)
	}
	if collections != 1 || members != 4 {
		t.Fatalf("counts = collections:%d members:%d, want 1/4", collections, members)
	}

	queue, ok, err := c.CollectionForVideo("bilibili", "BV1ep3")
	if err != nil || !ok {
		t.Fatalf("CollectionForVideo: ok=%v err=%v", ok, err)
	}
	if queue.ID != "season-1" || queue.Title != "测试合集" {
		t.Fatalf("collection meta = %+v", queue)
	}
	if len(queue.Items) != 3 || queue.CurrentIndex != 1 {
		t.Fatalf("queue len=%d index=%d, want 3/1 (only approved)", len(queue.Items), queue.CurrentIndex)
	}
	for i, want := range []string{"BV1ep1", "BV1ep3", "BV1ep4"} {
		if queue.Items[i].NativeID != want {
			t.Fatalf("queue[%d] = %s, want %s", i, queue.Items[i].NativeID, want)
		}
	}

	// 未过审的 ep2 仍在合集关系里,但不在可播放队列中。
	pending, ok, err := c.CollectionForVideo("bilibili", "BV1ep2")
	if err != nil || !ok {
		t.Fatalf("pending membership should exist: ok=%v err=%v", ok, err)
	}
	if pending.CurrentIndex != -1 || len(pending.Items) != 3 {
		t.Fatalf("pending queue = index:%d items:%d, want -1/3", pending.CurrentIndex, len(pending.Items))
	}

	if _, ok, _ := c.CollectionForVideo("bilibili", "BVmissing"); ok {
		t.Fatal("unknown video must not have a collection")
	}

	// 全量替换:旧合集/成员必须消失,不残留可播放队列。
	if _, _, err := c.ReplaceCollections("bilibili", []CollectionImport{
		{ID: "season-2", Title: "新合集", VideoIDs: []string{"BV1ep3"}},
	}); err != nil {
		t.Fatalf("replace again: %v", err)
	}
	if _, ok, _ := c.CollectionForVideo("bilibili", "BV1ep1"); ok {
		t.Fatal("old collection member must be replaced")
	}
	queue2, ok, err := c.CollectionForVideo("bilibili", "BV1ep3")
	if err != nil || !ok || queue2.ID != "season-2" || len(queue2.Items) != 1 {
		t.Fatalf("new collection = %+v ok=%v err=%v", queue2, ok, err)
	}
}

func TestReplaceCollectionsValidationAndIsolation(t *testing.T) {
	c := openTestCatalog(t)
	if _, _, err := c.ReplaceCollections("", nil); err == nil {
		t.Fatal("empty provider must error")
	}
	if _, _, err := c.ReplaceCollections("bilibili", []CollectionImport{
		{ID: "same"}, {ID: "same"},
	}); err == nil {
		t.Fatal("duplicate collection id must error")
	}
	// 其他 provider 的快照不受影响。
	mustUpsert(t, c, "bilibili", "BV1b", "b", 60)
	mustUpsert(t, c, "youtube", "yt12345678", "y", 60)
	approveTo(t, c, "movies", "bilibili", "BV1b")
	approveTo(t, c, "movies", "youtube", "yt12345678")
	if _, _, err := c.ReplaceCollections("bilibili", []CollectionImport{{ID: "b", VideoIDs: []string{"BV1b"}}}); err != nil {
		t.Fatalf("bili replace: %v", err)
	}
	if _, _, err := c.ReplaceCollections("youtube", []CollectionImport{{ID: "y", VideoIDs: []string{"yt12345678"}}}); err != nil {
		t.Fatalf("yt replace: %v", err)
	}
	if q, ok, _ := c.CollectionForVideo("bilibili", "BV1b"); !ok || q.ID != "b" {
		t.Fatalf("bilibili collection lost: %+v", q)
	}
	if q, ok, _ := c.CollectionForVideo("youtube", "yt12345678"); !ok || q.ID != "y" {
		t.Fatalf("youtube collection lost: %+v", q)
	}
}

func TestCollectionsExport(t *testing.T) {
	c := openTestCatalog(t)
	for _, id := range []string{"BV1x", "BV1y"} {
		mustUpsert(t, c, "bilibili", id, id, 60)
	}
	if _, _, err := c.ReplaceCollections("bilibili", []CollectionImport{
		{ID: "c1", Title: "合集一", VideoIDs: []string{"BV1x", "BV1y"}},
	}); err != nil {
		t.Fatalf("replace: %v", err)
	}
	all, err := c.Collections("")
	if err != nil {
		t.Fatalf("collections: %v", err)
	}
	if len(all) != 1 || all[0].ID != "c1" || all[0].Title != "合集一" || len(all[0].VideoIDs) != 2 {
		t.Fatalf("export = %+v", all)
	}
	if n, err := c.CountCollections(); err != nil || n != 1 {
		t.Fatalf("count = %d err=%v, want 1", n, err)
	}
}

func TestDeleteMemberCleansOrphanCollectionRow(t *testing.T) {
	c := openTestCatalog(t)
	mustUpsert(t, c, "bilibili", "BV1only", "only", 60)
	approveTo(t, c, "movies", "bilibili", "BV1only")
	if _, _, err := c.ReplaceCollections("bilibili", []CollectionImport{
		{ID: "c1", VideoIDs: []string{"BV1only"}},
	}); err != nil {
		t.Fatalf("replace: %v", err)
	}
	if _, videoDeleted, err := c.DeleteMember("movies", "bilibili", "BV1only"); err != nil || !videoDeleted {
		t.Fatalf("delete: deleted=%v err=%v", videoDeleted, err)
	}
	var n int
	if err := c.db.QueryRow(`SELECT COUNT(*) FROM collection_members WHERE provider='bilibili' AND native_id='BV1only'`).Scan(&n); err != nil {
		t.Fatalf("count members: %v", err)
	}
	if n != 0 {
		t.Fatalf("orphan collection member remains: %d", n)
	}
}
