package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// resolveDiskCache 把成功的 YouTube resolve 结果持久化到独立 SQLite 文件:
// 进程重启/发布后仍能命中,YouTube 播放从"重新跑 yt-dlp(冷解析 25-30s)"
// 变成毫秒回放。
//
// 与 catalog 分库:resolve 缓存是纯可重建数据,不需要进 catalog 的备份/审核体系;
// 单独文件也避免缓存写放大影响目录查询。打开/读写失败一律降级为内存缓存,
// 不影响主链路。
const (
	// 磁盘正缓存 TTL:YouTube 直链时效约 6h,取 1h 保守复用;进程重启后仍有
	// 相当高命中率(追剧/反复看同一视频),又不会让过期直链大面积回放。
	resolveDiskCacheTTL = time.Hour
	// 行数上限:单条 payload 是裁剪后的 KB 级结果,500 条约几 MB,容器 64M 内存可承受。
	resolveDiskCacheMaxRows = 500
	resolveDiskTrimEvery    = 32
)

type resolveDiskCache struct {
	db     *sql.DB
	ttl    time.Duration
	mu     sync.Mutex
	writes int
}

func openResolveDiskCache(path string) (*resolveDiskCache, error) {
	trimmed := strings.TrimSpace(path)
	if trimmed == "" {
		return nil, fmt.Errorf("resolve cache path is empty")
	}
	if dir := filepath.Dir(trimmed); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create resolve cache dir: %w", err)
		}
	}
	q := url.Values{
		"_pragma": []string{
			"busy_timeout(5000)",
			"journal_mode(WAL)",
			"synchronous(NORMAL)",
			// 容器只有 64M 内存:缓存页压到 1MB,别让第二个 SQLite 文件吃内存。
			"cache_size(-1024)",
		},
	}
	dsn := fmt.Sprintf("file:%s?%s", trimmed, q.Encode())
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open resolve cache: %w", err)
	}
	db.SetMaxOpenConns(1)
	cache := &resolveDiskCache{db: db, ttl: resolveDiskCacheTTL}
	if err := cache.ensureSchema(); err != nil {
		_ = db.Close()
		return nil, err
	}
	if err := cache.pruneExpired(); err != nil {
		log.Printf("resolve cache prune: %v", err)
	}
	if err := cache.trim(); err != nil {
		log.Printf("resolve cache trim: %v", err)
	}
	return cache, nil
}

func (c *resolveDiskCache) ensureSchema() error {
	const schema = `
CREATE TABLE IF NOT EXISTS resolve_cache (
  video_id   TEXT PRIMARY KEY,
  payload    BLOB NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_resolve_cache_expires ON resolve_cache(expires_at);
`
	if _, err := c.db.Exec(schema); err != nil {
		return fmt.Errorf("ensure resolve cache schema: %w", err)
	}
	return nil
}

func (c *resolveDiskCache) close() error {
	if c == nil || c.db == nil {
		return nil
	}
	return c.db.Close()
}

func (c *resolveDiskCache) lookup(videoID string) (PlaybackResultResponse, bool) {
	var (
		payload   []byte
		expiresAt int64
	)
	err := c.db.QueryRow(
		`SELECT payload, expires_at FROM resolve_cache WHERE video_id = ?`, videoID,
	).Scan(&payload, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return PlaybackResultResponse{}, false
	}
	if err != nil {
		log.Printf("resolve cache read %s: %v", videoID, err)
		return PlaybackResultResponse{}, false
	}
	if time.Now().Unix() >= expiresAt {
		c.delete(videoID)
		return PlaybackResultResponse{}, false
	}
	var result PlaybackResultResponse
	if err := json.Unmarshal(payload, &result); err != nil {
		log.Printf("resolve cache decode %s: %v", videoID, err)
		c.delete(videoID)
		return PlaybackResultResponse{}, false
	}
	if len(result.Options) == 0 {
		c.delete(videoID)
		return PlaybackResultResponse{}, false
	}
	return result, true
}

func (c *resolveDiskCache) store(videoID string, result PlaybackResultResponse) {
	if c == nil || c.db == nil || len(result.Options) == 0 {
		return
	}
	payload, err := json.Marshal(result)
	if err != nil {
		log.Printf("resolve cache encode %s: %v", videoID, err)
		return
	}
	now := time.Now()
	_, err = c.db.Exec(
		`INSERT INTO resolve_cache (video_id, payload, expires_at, created_at) VALUES (?, ?, ?, ?)
ON CONFLICT(video_id) DO UPDATE SET
  payload = excluded.payload,
  expires_at = excluded.expires_at,
  created_at = excluded.created_at`,
		videoID, payload, now.Add(c.ttl).Unix(), now.Unix(),
	)
	if err != nil {
		log.Printf("resolve cache write %s: %v", videoID, err)
		return
	}
	c.mu.Lock()
	c.writes++
	shouldTrim := c.writes%resolveDiskTrimEvery == 0
	c.mu.Unlock()
	if shouldTrim {
		if err := c.trim(); err != nil {
			log.Printf("resolve cache trim: %v", err)
		}
	}
}

func (c *resolveDiskCache) delete(videoID string) {
	if _, err := c.db.Exec(`DELETE FROM resolve_cache WHERE video_id = ?`, videoID); err != nil {
		log.Printf("resolve cache delete %s: %v", videoID, err)
	}
}

func (c *resolveDiskCache) pruneExpired() error {
	_, err := c.db.Exec(`DELETE FROM resolve_cache WHERE expires_at <= ?`, time.Now().Unix())
	return err
}

func (c *resolveDiskCache) trim() error {
	_, err := c.db.Exec(
		`DELETE FROM resolve_cache WHERE video_id NOT IN (
  SELECT video_id FROM resolve_cache ORDER BY created_at DESC LIMIT ?
)`, resolveDiskCacheMaxRows)
	return err
}
