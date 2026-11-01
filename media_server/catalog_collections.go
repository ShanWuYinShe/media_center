package main

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// CollectionImport 是合集导入的一条:ID/标题 + 有序的 nativeId 列表。
// 剧集顺序即 position(0 开始),与来源(如 B 站 ugc_season episodes)一致。
type CollectionImport struct {
	ID       string
	Title    string
	VideoIDs []string
}

// VideoCollectionQueue 是某视频所在合集的播放队列:只含已审核(approved)
// 的剧集,按合集顺序排列。CurrentIndex 是当前视频在 Items 中的下标;
// -1 表示当前视频不在已审核队列里(数据异常或未过审)。
type VideoCollectionQueue struct {
	ID           string
	Title        string
	Items        []CatalogVideo
	CurrentIndex int
}

// CollectionExport 是导出/备份用的合集快照。
type CollectionExport struct {
	ID       string   `json:"id"`
	Title    string   `json:"title"`
	VideoIDs []string `json:"videoIds"`
}

// ReplaceCollections 用一次全量快照替换某 provider 的合集关系(事务内)。
// 只写分组元数据,不新增视频、不改审核状态;空 provider / 重复合集 ID
// 直接报错,避免半截数据覆盖旧快照。
func (c *Catalog) ReplaceCollections(provider string, collections []CollectionImport) (int, int, error) {
	provider = strings.ToLower(strings.TrimSpace(provider))
	if provider == "" {
		return 0, 0, fmt.Errorf("replace collections: provider required")
	}
	if c == nil {
		return 0, 0, fmt.Errorf("replace collections: catalog not open")
	}
	seen := make(map[string]struct{}, len(collections))
	normalized := make([]CollectionImport, 0, len(collections))
	for i, item := range collections {
		id := strings.TrimSpace(item.ID)
		if id == "" {
			return 0, 0, fmt.Errorf("collection %d: id required", i)
		}
		if _, ok := seen[id]; ok {
			return 0, 0, fmt.Errorf("collection %d: duplicate id %q", i, id)
		}
		seen[id] = struct{}{}
		videoSeen := make(map[string]struct{}, len(item.VideoIDs))
		videoIDs := make([]string, 0, len(item.VideoIDs))
		for _, raw := range item.VideoIDs {
			nativeID := stripPageSuffix(provider, strings.TrimSpace(raw))
			if nativeID == "" {
				continue
			}
			if _, ok := videoSeen[nativeID]; ok {
				continue
			}
			videoSeen[nativeID] = struct{}{}
			videoIDs = append(videoIDs, nativeID)
		}
		normalized = append(normalized, CollectionImport{
			ID:       id,
			Title:    strings.TrimSpace(item.Title),
			VideoIDs: videoIDs,
		})
	}

	tx, err := c.db.Begin()
	if err != nil {
		return 0, 0, fmt.Errorf("begin collections replace: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback()
		}
	}()
	if _, err = tx.Exec(`DELETE FROM collection_members WHERE provider = ?`, provider); err != nil {
		return 0, 0, fmt.Errorf("clear collection members: %w", err)
	}
	if _, err = tx.Exec(`DELETE FROM collections WHERE provider = ?`, provider); err != nil {
		return 0, 0, fmt.Errorf("clear collections: %w", err)
	}
	now := time.Now().UTC().Format(time.RFC3339)
	members := 0
	for _, item := range normalized {
		if _, err = tx.Exec(`
INSERT INTO collections (provider, collection_id, title, updated_at)
VALUES (?, ?, ?, ?)`, provider, item.ID, item.Title, now); err != nil {
			return 0, 0, fmt.Errorf("insert collection %s: %w", item.ID, err)
		}
		for position, nativeID := range item.VideoIDs {
			if _, err = tx.Exec(`
INSERT INTO collection_members (provider, collection_id, native_id, position)
VALUES (?, ?, ?, ?)`, provider, item.ID, nativeID, position); err != nil {
				return 0, 0, fmt.Errorf("insert collection member %s/%s: %w", item.ID, nativeID, err)
			}
			members++
		}
	}
	if err = tx.Commit(); err != nil {
		return 0, 0, fmt.Errorf("commit collections replace: %w", err)
	}
	return len(normalized), members, nil
}

// CollectionForVideo 查询某视频所在合集及“已审核”剧集队列。
// 未找到合集返回 (zero,false,nil);当前视频不在队列中时 CurrentIndex=-1。
func (c *Catalog) CollectionForVideo(provider, videoID string) (VideoCollectionQueue, bool, error) {
	provider = strings.ToLower(strings.TrimSpace(provider))
	videoID = strings.TrimSpace(videoID)
	if c == nil || provider == "" || videoID == "" {
		return VideoCollectionQueue{}, false, nil
	}
	// 兼容存量 "bvid_pN" 分P条:合集关系按 base bvid 存。
	videoID = stripPageSuffix(provider, videoID)
	var collectionID, title string
	err := c.db.QueryRow(`
SELECT cm.collection_id, COALESCE(c.title, '')
FROM collection_members cm
LEFT JOIN collections c ON c.provider = cm.provider AND c.collection_id = cm.collection_id
WHERE cm.provider = ? AND cm.native_id = ?
LIMIT 1`, provider, videoID).Scan(&collectionID, &title)
	if errors.Is(err, sql.ErrNoRows) {
		return VideoCollectionQueue{}, false, nil
	}
	if err != nil {
		return VideoCollectionQueue{}, false, fmt.Errorf("find collection for video: %w", err)
	}
	rows, err := c.db.Query(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM collection_members cm
JOIN videos v ON v.provider = cm.provider AND v.native_id = cm.native_id
WHERE cm.provider = ? AND cm.collection_id = ?
  AND EXISTS (
    SELECT 1 FROM category_members m
    WHERE m.provider = v.provider AND m.native_id = v.native_id AND m.status = 'approved'
  )
  AND `+notPageGlobCond("cm.provider", "v.native_id")+`
ORDER BY cm.position ASC, v.native_id ASC`, provider, collectionID)
	if err != nil {
		return VideoCollectionQueue{}, false, fmt.Errorf("query collection queue: %w", err)
	}
	defer rows.Close()
	queue := VideoCollectionQueue{ID: collectionID, Title: title, CurrentIndex: -1}
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var description, year, thumb, source sql.NullString
		var duration sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &description, &creators, &duration, &year, &thumb, &source); err != nil {
			return VideoCollectionQueue{}, false, fmt.Errorf("scan collection queue: %w", err)
		}
		v.Description = description.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = source.String
		v.DurationSec = duration.Int64
		v.Creators = decodeCreators(creators.String)
		if v.NativeID == videoID {
			queue.CurrentIndex = len(queue.Items)
		}
		queue.Items = append(queue.Items, v)
	}
	if err := rows.Err(); err != nil {
		return VideoCollectionQueue{}, false, err
	}
	return queue, true, nil
}

// Collections 返回某 provider(空=全部)的合集快照,供内部导出/备份。
func (c *Catalog) Collections(provider string) ([]CollectionExport, error) {
	if c == nil {
		return nil, nil
	}
	provider = strings.ToLower(strings.TrimSpace(provider))
	var (
		query string
		args  []any
	)
	query = `
SELECT c.provider, c.collection_id, c.title, cm.native_id
FROM collections c
LEFT JOIN collection_members cm ON cm.provider = c.provider AND cm.collection_id = c.collection_id
WHERE 1=1`
	if provider != "" {
		query += ` AND c.provider = ?`
		args = append(args, provider)
	}
	query += ` ORDER BY c.provider ASC, c.collection_id ASC, cm.position ASC`
	rows, err := c.db.Query(query, args...)
	if err != nil {
		return nil, fmt.Errorf("query collections: %w", err)
	}
	defer rows.Close()
	out := make([]CollectionExport, 0)
	index := make(map[string]int)
	for rows.Next() {
		var p, id, title string
		var nativeID sql.NullString
		if err := rows.Scan(&p, &id, &title, &nativeID); err != nil {
			return nil, fmt.Errorf("scan collection export: %w", err)
		}
		key := p + "|" + id
		pos, ok := index[key]
		if !ok {
			out = append(out, CollectionExport{ID: id, Title: title})
			pos = len(out) - 1
			index[key] = pos
		}
		if nativeID.Valid && nativeID.String != "" {
			out[pos].VideoIDs = append(out[pos].VideoIDs, nativeID.String)
		}
	}
	return out, rows.Err()
}

// CountCollections 返回合集总数(内部概览/运维诊断用)。
func (c *Catalog) CountCollections() (int, error) {
	if c == nil {
		return 0, nil
	}
	var n int
	if err := c.db.QueryRow(`SELECT COUNT(*) FROM collections`).Scan(&n); err != nil {
		return 0, fmt.Errorf("count collections: %w", err)
	}
	return n, nil
}
