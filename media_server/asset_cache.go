package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var errAssetCacheMiss = errors.New("asset cache miss")

type AssetCache struct {
	dir      string
	maxBytes int64
	mu       sync.Mutex
}

type cachedAssetMeta struct {
	Status    int         `json:"status"`
	Header    http.Header `json:"header"`
	Size      int64       `json:"size"`
	CreatedAt time.Time   `json:"createdAt"`
}

type CachedAsset struct {
	meta cachedAssetMeta
	file *os.File
}

type assetCacheRecorder struct {
	cache *AssetCache
	key   string
	meta  cachedAssetMeta
	path  string
	file  *os.File
	size  int64
	err   error
}

func NewAssetCache(dir string, maxBytes int64) *AssetCache {
	dir = strings.TrimSpace(dir)
	if dir == "" || maxBytes <= 0 {
		return nil
	}
	return &AssetCache{dir: dir, maxBytes: maxBytes}
}

func (c *AssetCache) Key(payload assetPayload, r *http.Request) string {
	if c == nil {
		return ""
	}
	h := sha256.New()
	writeCacheKeyPart(h, payload.URL)
	writeCacheKeyHeaders(h, payload.Headers)
	for _, name := range forwardedRequestHeaders {
		writeCacheKeyPart(h, strings.ToLower(name))
		writeCacheKeyPart(h, r.Header.Get(name))
	}
	return hex.EncodeToString(h.Sum(nil))
}

func (c *AssetCache) Get(key string) (*CachedAsset, error) {
	if c == nil || key == "" {
		return nil, errAssetCacheMiss
	}
	metaPath := c.metaPath(key)
	dataPath := c.dataPath(key)
	raw, err := os.ReadFile(metaPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, errAssetCacheMiss
		}
		return nil, err
	}
	var meta cachedAssetMeta
	if err := json.Unmarshal(raw, &meta); err != nil {
		_ = os.Remove(metaPath)
		_ = os.Remove(dataPath)
		return nil, errAssetCacheMiss
	}
	file, err := os.Open(dataPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			_ = os.Remove(metaPath)
			return nil, errAssetCacheMiss
		}
		return nil, err
	}
	if info, err := file.Stat(); err != nil || info.Size() != meta.Size {
		_ = file.Close()
		_ = os.Remove(metaPath)
		_ = os.Remove(dataPath)
		return nil, errAssetCacheMiss
	}
	now := time.Now()
	_ = os.Chtimes(metaPath, now, now)
	_ = os.Chtimes(dataPath, now, now)
	return &CachedAsset{meta: meta, file: file}, nil
}

func (c *AssetCache) NewRecorder(key string, status int, header http.Header) (*assetCacheRecorder, error) {
	if c == nil || key == "" || (status != http.StatusOK && status != http.StatusPartialContent) {
		return nil, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	dir := c.shardDir(key)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	file, err := os.CreateTemp(dir, ".asset-*.tmp")
	if err != nil {
		return nil, err
	}
	return &assetCacheRecorder{
		cache: c,
		key:   key,
		path:  file.Name(),
		file:  file,
		meta: cachedAssetMeta{
			Status:    status,
			Header:    cloneHeader(header),
			CreatedAt: time.Now(),
		},
	}, nil
}

func (c *AssetCache) dataPath(key string) string {
	return filepath.Join(c.shardDir(key), key+".body")
}

func (c *AssetCache) metaPath(key string) string {
	return filepath.Join(c.shardDir(key), key+".json")
}

func (c *AssetCache) shardDir(key string) string {
	shard := "xx"
	if len(key) >= 2 {
		shard = key[:2]
	}
	return filepath.Join(c.dir, shard)
}

func (c *CachedAsset) Header() http.Header {
	return cloneHeader(c.meta.Header)
}

func (c *CachedAsset) Status() int {
	return c.meta.Status
}

func (c *CachedAsset) Body() io.Reader {
	return c.file
}

func (c *CachedAsset) Close() error {
	return c.file.Close()
}

func (w *assetCacheRecorder) Write(p []byte) (int, error) {
	if w == nil || w.file == nil || w.err != nil {
		return len(p), nil
	}
	if w.size+int64(len(p)) > w.cache.maxBytes {
		w.err = errors.New("asset exceeds cache size limit")
		_ = w.file.Close()
		_ = os.Remove(w.path)
		w.file = nil
		return len(p), nil
	}
	n, err := w.file.Write(p)
	w.size += int64(n)
	if err != nil {
		w.err = err
		_ = w.file.Close()
		_ = os.Remove(w.path)
		w.file = nil
	}
	return len(p), nil
}

func (w *assetCacheRecorder) Commit() error {
	if w == nil || w.file == nil {
		return nil
	}
	if err := w.file.Close(); err != nil && w.err == nil {
		w.err = err
	}
	if w.err != nil {
		_ = os.Remove(w.path)
		return w.err
	}
	w.meta.Size = w.size
	w.meta.Header.Set("Content-Length", strconv.FormatInt(w.size, 10))
	metaBytes, err := json.Marshal(w.meta)
	if err != nil {
		_ = os.Remove(w.path)
		return err
	}
	cache := w.cache
	cache.mu.Lock()
	defer cache.mu.Unlock()
	metaPath := cache.metaPath(w.key)
	dataPath := cache.dataPath(w.key)
	tmpMeta := metaPath + ".tmp"
	if err := os.WriteFile(tmpMeta, metaBytes, 0o644); err != nil {
		_ = os.Remove(w.path)
		return err
	}
	if err := os.Rename(w.path, dataPath); err != nil {
		_ = os.Remove(tmpMeta)
		_ = os.Remove(w.path)
		return err
	}
	if err := os.Rename(tmpMeta, metaPath); err != nil {
		_ = os.Remove(dataPath)
		_ = os.Remove(tmpMeta)
		return err
	}
	return cache.pruneLocked()
}

func (w *assetCacheRecorder) Discard() {
	if w == nil {
		return
	}
	if w.file != nil {
		_ = w.file.Close()
	}
	_ = os.Remove(w.path)
}

func (c *AssetCache) pruneLocked() error {
	type entry struct {
		metaPath string
		dataPath string
		size     int64
		modTime  time.Time
	}
	var entries []entry
	var total int64
	err := filepath.WalkDir(c.dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || filepath.Ext(path) != ".json" {
			return err
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		var meta cachedAssetMeta
		if err := json.Unmarshal(raw, &meta); err != nil {
			return os.Remove(path)
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		dataPath := strings.TrimSuffix(path, ".json") + ".body"
		entries = append(entries, entry{metaPath: path, dataPath: dataPath, size: meta.Size, modTime: info.ModTime()})
		total += meta.Size
		return nil
	})
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].modTime.Before(entries[j].modTime)
	})
	for _, item := range entries {
		if total <= c.maxBytes {
			break
		}
		_ = os.Remove(item.metaPath)
		_ = os.Remove(item.dataPath)
		total -= item.size
	}
	return nil
}

func writeCacheKeyPart(w io.Writer, value string) {
	_, _ = io.WriteString(w, value)
	_, _ = io.WriteString(w, "\x00")
}

func writeCacheKeyHeaders(w io.Writer, headers map[string]string) {
	keys := make([]string, 0, len(headers))
	for key := range headers {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		writeCacheKeyPart(w, strings.ToLower(key))
		writeCacheKeyPart(w, headers[key])
	}
}

func cloneHeader(header http.Header) http.Header {
	out := make(http.Header, len(header))
	for key, values := range header {
		out[key] = append([]string(nil), values...)
	}
	return out
}
