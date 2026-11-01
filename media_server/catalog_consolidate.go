package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// ConsolidatePages 清理 catalog 中已有的分 P 脏数据:合并同 base native_id 的 _pN 条目。
func (c *Catalog) ConsolidatePages() (groups int, deleted int, created int, err error) {
	// Step 1: 把所有 _pN 条目读入内存(提前释放连接,避免随后的事务死锁)。
	// 精确按「_p<纯数字> 结尾」选中(与公开读路径的排除定义用同一 helper),
	// 不把中间含 _p 的合法 ID 拉进合并逻辑。
	rows, err := c.db.Query(`
SELECT v.provider, v.native_id, v.title, v.description, v.creators, v.duration_sec, v.year, v.thumbnail_url, v.source_url
FROM videos v
WHERE ` + pageGlobCond("v.provider", "v.native_id") + `
ORDER BY v.provider, v.native_id`)
	if err != nil {
		return 0, 0, 0, fmt.Errorf("query page videos: %w", err)
	}

	type pageEntry struct{ CatalogVideo }
	pageMap := make(map[string][]pageEntry)
	var allKeys []string
	for rows.Next() {
		var v CatalogVideo
		var creators sql.NullString
		var desc, year, thumb, src sql.NullString
		var dur sql.NullInt64
		if err := rows.Scan(&v.Provider, &v.NativeID, &v.Title, &desc, &creators, &dur, &year, &thumb, &src); err != nil {
			rows.Close()
			return 0, 0, 0, fmt.Errorf("scan page video: %w", err)
		}
		v.Description = desc.String
		v.Year = year.String
		v.ThumbnailURL = thumb.String
		v.SourceURL = src.String
		v.DurationSec = dur.Int64
		v.Creators = decodeCreators(creators.String)

		baseID := stripPageSuffix(v.Provider, v.NativeID)
		key := v.Provider + "|" + baseID
		if _, ok := pageMap[key]; !ok {
			allKeys = append(allKeys, key)
		}
		pageMap[key] = append(pageMap[key], pageEntry{v})
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, 0, 0, err
	}

	// Step 2: 读取所有 _pN 的分类成员(一次查出,避免逐条 Query)
	memberSQL := `SELECT provider, native_id, category_id, status, score, pinned, rank, COALESCE(reviewer,''), COALESCE(reviewed_at,'') FROM category_members WHERE provider=? AND native_id=?`
	type memberRow struct {
		provider, nativeID, catID, status, reviewer, reviewedAt string
		score, pinned                                           int64
		rank                                                    sql.NullInt64
	}
	pageMembers := make(map[string][]memberRow) // key: provider|native_id
	for _, key := range allKeys {
		entries := pageMap[key]
		for _, entry := range entries {
			mrows, qerr := c.db.Query(memberSQL, entry.Provider, entry.NativeID)
			if qerr != nil {
				return 0, 0, 0, fmt.Errorf("query members for %s/%s: %w", entry.Provider, entry.NativeID, qerr)
			}
			for mrows.Next() {
				var mr memberRow
				if err := mrows.Scan(&mr.provider, &mr.nativeID, &mr.catID, &mr.status, &mr.score, &mr.pinned, &mr.rank, &mr.reviewer, &mr.reviewedAt); err != nil {
					mrows.Close()
					return 0, 0, 0, fmt.Errorf("scan member: %w", err)
				}
				mkey := entry.Provider + "|" + entry.NativeID
				pageMembers[mkey] = append(pageMembers[mkey], mr)
			}
			mrows.Close()
		}
	}

	// Step 3: 检查哪些 base 视频已存在
	baseExists := make(map[string]bool)
	for _, key := range allKeys {
		parts := strings.SplitN(key, "|", 2)
		var exists bool
		_ = c.db.QueryRow(`SELECT 1 FROM videos WHERE provider=? AND native_id=?`, parts[0], parts[1]).Scan(&exists)
		baseExists[key] = exists
	}

	// Step 4: 在一个事务内完成所有写入
	tx, err := c.db.Begin()
	if err != nil {
		return 0, 0, 0, fmt.Errorf("begin tx: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback()
		}
	}()

	for _, key := range allKeys {
		entries := pageMap[key]
		parts := strings.SplitN(key, "|", 2)
		provider := parts[0]
		baseID := parts[1]

		if !baseExists[key] {
			first := entries[0]
			var totalSec int64
			for _, e := range entries {
				totalSec += e.DurationSec
			}
			creatorsJSON, _ := json.Marshal(first.Creators)
			if _, err = tx.Exec(`
INSERT INTO videos (provider, native_id, title, description, creators, duration_sec, year, thumbnail_url, source_url, fetched_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				provider, baseID, first.Title, first.Description, string(creatorsJSON),
				totalSec, first.Year, first.ThumbnailURL, first.SourceURL, time.Now().UTC().Format(time.RFC3339)); err != nil {
				return 0, 0, 0, fmt.Errorf("create base video %s/%s: %w", provider, baseID, err)
			}
			created++
		}

		for _, entry := range entries {
			mkey := entry.Provider + "|" + entry.NativeID
			for _, mr := range pageMembers[mkey] {
				if _, err = tx.Exec(`
INSERT INTO category_members (category_id, provider, native_id, status, score, pinned, rank, reviewer, reviewed_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(category_id, provider, native_id) DO NOTHING`,
					mr.catID, provider, baseID, mr.status, mr.score, mr.pinned, mr.rank, mr.reviewer, mr.reviewedAt); err != nil {
					return 0, 0, 0, fmt.Errorf("insert base member %s/%s/%s: %w", mr.catID, provider, baseID, err)
				}
			}
		}

		for _, entry := range entries {
			if _, err = tx.Exec(`DELETE FROM category_members WHERE provider=? AND native_id=?`, provider, entry.NativeID); err != nil {
				return 0, 0, 0, fmt.Errorf("delete member %s/%s: %w", provider, entry.NativeID, err)
			}
			res, delErr := tx.Exec(`DELETE FROM videos WHERE provider=? AND native_id=?`, provider, entry.NativeID)
			if delErr != nil {
				return 0, 0, 0, fmt.Errorf("delete video %s/%s: %w", provider, entry.NativeID, delErr)
			}
			if n, _ := res.RowsAffected(); n > 0 {
				deleted++
			}
		}
		groups++
	}

	if err = tx.Commit(); err != nil {
		return 0, 0, 0, fmt.Errorf("commit: %w", err)
	}
	return groups, deleted, created, nil
}
