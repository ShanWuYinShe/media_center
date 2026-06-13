package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// CategorySnapshot 是某个分类预聚合好的成品视频列表(已打分/过滤/去重/审校),
// 持久化到磁盘后请求分页直接从中切片返回,不再每次实时打上游。
type CategorySnapshot struct {
	CategoryID string         `json:"categoryId"`
	UpdatedAt  time.Time      `json:"updatedAt"`
	Items      []VideoSummary `json:"items"`
}

// SnapshotStore 是分类快照的内存 + 磁盘存储。读多写少:请求读用 RLock,
// 后台刷新整体替换某分类的快照(新切片),在途读者持有旧切片不受影响。
type SnapshotStore struct {
	mu   sync.RWMutex
	dir  string
	data map[string]CategorySnapshot
}

func NewSnapshotStore(dir string) (*SnapshotStore, error) {
	store := &SnapshotStore{
		dir:  strings.TrimSpace(dir),
		data: make(map[string]CategorySnapshot),
	}
	if store.dir != "" {
		if err := os.MkdirAll(store.dir, 0o755); err != nil {
			return nil, fmt.Errorf("create snapshot dir: %w", err)
		}
		store.loadFromDisk()
	}
	return store, nil
}

func (s *SnapshotStore) loadFromDisk() {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		raw, err := os.ReadFile(filepath.Join(s.dir, entry.Name()))
		if err != nil {
			continue
		}
		var snap CategorySnapshot
		if err := json.Unmarshal(raw, &snap); err != nil {
			log.Printf("snapshot: skip unreadable %s: %v", entry.Name(), err)
			continue
		}
		if snap.CategoryID == "" || len(snap.Items) == 0 {
			continue
		}
		s.data[snap.CategoryID] = snap
	}
	if len(s.data) > 0 {
		log.Printf("snapshot: loaded %d category snapshot(s) from %s", len(s.data), s.dir)
	}
}

func (s *SnapshotStore) Get(categoryID string) (CategorySnapshot, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	snap, ok := s.data[categoryID]
	return snap, ok
}

func (s *SnapshotStore) Set(snap CategorySnapshot) error {
	s.mu.Lock()
	s.data[snap.CategoryID] = snap
	s.mu.Unlock()
	return s.persist(snap)
}

func (s *SnapshotStore) persist(snap CategorySnapshot) error {
	if s.dir == "" {
		return nil
	}
	raw, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		return err
	}
	path := filepath.Join(s.dir, snapshotFileName(snap.CategoryID))
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// snapshotFileName 把分类 ID 规整成安全文件名(分类 ID 本就是 slug,这里只是兜底)。
func snapshotFileName(categoryID string) string {
	safe := strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_':
			return r
		default:
			return '_'
		}
	}, categoryID)
	if safe == "" {
		safe = "category"
	}
	return safe + ".json"
}

// paginateSnapshot 从快照里切出某一页。务必复制切片,避免后续 delivery 重写污染
// 内存里共享的快照数据。
func paginateSnapshot(category ContentCategory, snap CategorySnapshot, page, pageSize int) SearchResponse {
	items := snap.Items
	start := (page - 1) * pageSize
	if start > len(items) {
		start = len(items)
	}
	end := min(start+pageSize, len(items))
	pageItems := append([]VideoSummary(nil), items[start:end]...)
	return SearchResponse{
		Query:    "",
		Category: &PublicCategory{ID: category.ID, Title: category.Title, Description: category.Description},
		Page:     page,
		PageSize: pageSize,
		Total:    len(items),
		Items:    pageItems,
	}
}

// snapshotFetchBudget 给快照构建一个更慷慨的上游抓取预算(快照在后台/离线生成,
// 不在乎延迟,优先把列表抓全抓准)。
func snapshotFetchBudget(targetSize int) int {
	budget := targetSize/4 + 8
	if budget < 12 {
		budget = 12
	}
	if budget > 60 {
		budget = 60
	}
	return budget
}

// buildCategorySnapshot 产出某分类快照。优先用“分类维护数据库(catalog)”中已审核(approved)
// 的固定列表物化(只套用 block 类审校剔噪、不重排,顺序由数据库决定);该分类在库中没有
// approved 行时,回退到实时聚合 + 完整审校(block + pin),与未接入 catalog 前行为一致。
func (s *mediaServer) buildCategorySnapshot(ctx context.Context, category ContentCategory) (CategorySnapshot, error) {
	target := s.config.SnapshotTargetSize
	if target <= 0 {
		target = 60
	}
	if s.catalog != nil {
		items, err := s.buildCategoryItemsFromCatalog(category, target)
		if err != nil {
			log.Printf("snapshot: catalog read failed category=%s, falling back to live: %v", category.ID, err)
		} else if len(items) > 0 {
			return CategorySnapshot{
				CategoryID: category.ID,
				UpdatedAt:  time.Now().UTC(),
				Items:      items,
			}, nil
		}
	}
	items, _, err := s.collectCategoryItems(ctx, category, target, snapshotFetchBudget(target))
	if err != nil {
		return CategorySnapshot{}, err
	}
	items = s.applyCuration(ctx, category.ID, items)
	if len(items) > target {
		items = items[:target]
	}
	return CategorySnapshot{
		CategoryID: category.ID,
		UpdatedAt:  time.Now().UTC(),
		Items:      items,
	}, nil
}

// buildCategoryItemsFromCatalog 把分类维护数据库里已审核的固定列表物化成快照条目。
// catalog 决定内容与顺序;这里只套用 block 类审校(剔除临时发现的噪声),封顶到 target。
func (s *mediaServer) buildCategoryItemsFromCatalog(category ContentCategory, target int) ([]VideoSummary, error) {
	approved, err := s.catalog.ApprovedForCategory(category.ID, target)
	if err != nil {
		return nil, err
	}
	if len(approved) == 0 {
		return nil, nil
	}
	items := make([]VideoSummary, 0, len(approved))
	for _, cv := range approved {
		items = append(items, s.summaryFromCatalogVideo(cv))
	}
	items = s.applyCurationBlocks(category.ID, items)
	if len(items) > target {
		items = items[:target]
	}
	return items, nil
}

// summaryFromCatalogVideo 把一行 CatalogVideo 转成运行时 VideoSummary(原始字段,
// 不做 delivery 重写——签名/代理仍在每次请求时统一处理)。缩略图按 provider 配头部,
// 以便 per-request 缩略图代理能带正确 Referer 抓到上游图片。
func (s *mediaServer) summaryFromCatalogVideo(cv CatalogVideo) VideoSummary {
	return VideoSummary{
		ID:               s.idCodec.EncodeProviderVideoID(cv.Provider, cv.NativeID),
		VideoKind:        "vod",
		Title:            cv.Title,
		Description:      cv.Description,
		Year:             cv.Year,
		Creators:         cv.Creators,
		ThumbnailURL:     cv.ThumbnailURL,
		ThumbnailHeaders: providerThumbnailHeaders(cv.Provider),
		DurationSeconds:  cv.DurationSec,
		PlaybackKind:     "vod",
	}
}

// providerThumbnailHeaders 返回某 provider 抓取缩略图所需的直连请求头(如 bilibili 的
// Referer)。未知 provider 返回 nil(交由 delivery 决定是否补默认头)。
func providerThumbnailHeaders(provider string) map[string]string {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "bilibili":
		return directThumbnailHeaders()
	default:
		return nil
	}
}

// refreshAllSnapshots 逐个分类重建快照。onlyStale=true 时跳过仍新鲜的分类(用于启动)。
// 构建失败或空结果时保留上一版快照,绝不把已有内容清空。
func (s *mediaServer) refreshAllSnapshots(ctx context.Context, onlyStale bool) {
	for _, category := range s.categories {
		if ctx.Err() != nil {
			return
		}
		if onlyStale {
			if snap, ok := s.snapshots.Get(category.ID); ok &&
				len(snap.Items) > 0 &&
				time.Since(snap.UpdatedAt) < s.config.SnapshotRefresh {
				continue
			}
		}
		buildCtx, cancel := context.WithTimeout(ctx, 3*time.Minute)
		snap, err := s.buildCategorySnapshot(buildCtx, category)
		cancel()
		if err != nil {
			log.Printf("snapshot: build failed category=%s: %v", category.ID, err)
			continue
		}
		if len(snap.Items) == 0 {
			log.Printf("snapshot: build empty category=%s, keeping previous", category.ID)
			continue
		}
		if err := s.snapshots.Set(snap); err != nil {
			log.Printf("snapshot: persist failed category=%s: %v", category.ID, err)
			continue
		}
		log.Printf("snapshot: built category=%s items=%d", category.ID, len(snap.Items))
		// 分类之间稍作间隔,避免对上游突发式连打。
		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}
}

// runSnapshotRefresher 启动时补齐缺失/过期的快照,之后按刷新间隔整体重建。
func (s *mediaServer) runSnapshotRefresher(ctx context.Context) {
	s.refreshAllSnapshots(ctx, true)
	ticker := time.NewTicker(s.config.SnapshotRefresh)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.refreshAllSnapshots(ctx, false)
		}
	}
}

// snapshotSummary 给离线构建命令打印一份分类条数概览。
func (s *mediaServer) snapshotSummary() string {
	var b strings.Builder
	ids := make([]string, 0, len(s.categories))
	for _, c := range s.categories {
		ids = append(ids, c.ID)
	}
	sort.Strings(ids)
	for _, id := range ids {
		snap, ok := s.snapshots.Get(id)
		if !ok {
			fmt.Fprintf(&b, "  %-16s (none)\n", id)
			continue
		}
		fmt.Fprintf(&b, "  %-16s %3d items @ %s\n", id, len(snap.Items), snap.UpdatedAt.Format(time.RFC3339))
	}
	return b.String()
}
