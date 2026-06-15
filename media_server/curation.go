package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"strings"
)

// 本地审校(curation):一个提交进仓库、可手工编辑的 curation.json,让人工把噪声
// 永久剔除、把精品强制置顶,从而保证分类结果更精确。
//
// ID 推荐用稳定的原生写法(provider:nativeID,如 bilibili:BV1xxx),不随签名密钥变化;
// 也兼容裸原生 ID(BV1xxx)和接口返回的公开签名 id。文件结构示例:
//
//	{
//	  "categories": {
//	    "movies": {
//	      "blockIds":           ["bilibili:BV1xxxx"],
//	      "blockTitleContains": ["预告", "解说"],
//	      "pinIds":             ["bilibili:BV1yyyy"]
//	    }
//	  }
//	}
type rawCuration struct {
	Categories map[string]rawCategoryCuration `json:"categories"`
}

type rawCategoryCuration struct {
	BlockIDs           []string `json:"blockIds"`
	BlockTitleContains []string `json:"blockTitleContains"`
	PinIDs             []string `json:"pinIds"`
}

// CategoryCuration 是某分类规整后的审校规则。
type CategoryCuration struct {
	BlockIDs           map[string]struct{}
	BlockTitleContains []string
	PinIDs             []string
}

// CurationConfig 按分类持有审校规则。文件缺失时为空(审校可选,不影响主流程)。
type CurationConfig struct {
	categories map[string]CategoryCuration
}

func loadCuration(path string) CurationConfig {
	cfg := CurationConfig{categories: map[string]CategoryCuration{}}
	if strings.TrimSpace(path) == "" {
		return cfg
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("curation: read %s failed: %v", path, err)
		}
		return cfg
	}
	var parsed rawCuration
	if err := json.Unmarshal(raw, &parsed); err != nil {
		log.Printf("curation: decode %s failed: %v", path, err)
		return cfg
	}
	for id, rc := range parsed.Categories {
		id = strings.TrimSpace(id)
		if id == "" {
			continue
		}
		cur := CategoryCuration{BlockIDs: map[string]struct{}{}}
		for _, x := range rc.BlockIDs {
			if t := strings.TrimSpace(x); t != "" {
				cur.BlockIDs[t] = struct{}{}
			}
		}
		for _, x := range rc.BlockTitleContains {
			if t := strings.ToLower(strings.TrimSpace(x)); t != "" {
				cur.BlockTitleContains = append(cur.BlockTitleContains, t)
			}
		}
		for _, x := range rc.PinIDs {
			if t := strings.TrimSpace(x); t != "" {
				cur.PinIDs = append(cur.PinIDs, t)
			}
		}
		cfg.categories[id] = cur
	}
	return cfg
}

func (c CurationConfig) forCategory(id string) (CategoryCuration, bool) {
	cur, ok := c.categories[id]
	return cur, ok
}

func (c CategoryCuration) isEmpty() bool {
	return len(c.BlockIDs) == 0 && len(c.BlockTitleContains) == 0 && len(c.PinIDs) == 0
}

// applyCuration 对聚合结果套用某分类的审校规则:先剔除黑名单(按 ID / 标题关键词),
// 再把置顶项(若已在列表中或可按 ID 拉到)挪到最前面。
//
// ID 匹配同时支持两种写法,后者推荐用于长期维护:
//   - 公开签名 ID:/videos 等接口返回的 item.id(随签名密钥变化,不稳定)。
//   - 稳定原生 ID:provider:nativeID(如 bilibili:BV1xxx)或裸原生 ID(如 BV1xxx),
//     不随签名密钥变化,适合外部工具长期维护。
func (s *mediaServer) applyCuration(ctx context.Context, categoryID string, items []VideoSummary) []VideoSummary {
	cur, ok := s.curation.forCategory(categoryID)
	if !ok || cur.isEmpty() {
		return items
	}
	filtered := s.applyCurationBlocks(categoryID, items)
	if len(cur.PinIDs) > 0 {
		filtered = s.applyPins(ctx, cur.PinIDs, filtered)
	}
	return filtered
}

// applyCurationBlocks 只套用某分类的“剔除”规则(按 ID / 标题关键词),不做置顶重排。
// 目录(catalog)路径用它:approved 列表的内容与顺序由数据库决定,审校文件这里只负责
// 把临时发现的噪声从已物化的固定列表里剔掉,不改变 catalog 决定的排序。
func (s *mediaServer) applyCurationBlocks(categoryID string, items []VideoSummary) []VideoSummary {
	cur, ok := s.curation.forCategory(categoryID)
	if !ok || (len(cur.BlockIDs) == 0 && len(cur.BlockTitleContains) == 0) {
		return items
	}
	blockKeys := s.nativeKeySet(cur.BlockIDs)
	filtered := make([]VideoSummary, 0, len(items))
	for _, item := range items {
		if _, blocked := cur.BlockIDs[item.ID]; blocked {
			continue
		}
		if _, blocked := blockKeys[s.nativeKey(item.ID)]; blocked {
			continue
		}
		if curationTitleBlocked(item, cur.BlockTitleContains) {
			continue
		}
		filtered = append(filtered, item)
	}
	return filtered
}

// resolveVideoRef 把任意形式的视频 ID 解析成 (provider, 原生ID)。接受三种写法:
// ① 公开签名 ID(/videos 等接口返回的 id);② 裸原生 ID(如 bilibili 的 BVxxx);
// ③ provider:nativeID 显式写法(如 bilibili:BV1xxx)。便于审校用稳定 ID 引用视频。
func (s *mediaServer) resolveVideoRef(id string) (provider string, nativeID string, ok bool) {
	id = strings.TrimSpace(id)
	if id == "" {
		return "", "", false
	}
	// provider:nativeID 显式写法。公开签名 ID(v1.base64.sig)与裸 BVID 都不含冒号,
	// 故只有显式写法会命中这里,不会误判。
	if i := strings.Index(id, ":"); i > 0 {
		p := strings.ToLower(strings.TrimSpace(id[:i]))
		n := strings.TrimSpace(id[i+1:])
		if p != "" && n != "" {
			return p, n, true
		}
	}
	if s.idCodec != nil {
		if ref, err := s.idCodec.DecodeVideoRef(id); err == nil && ref.Provider != "" && ref.ID != "" {
			return strings.ToLower(ref.Provider), ref.ID, true
		}
	}
	return "", "", false
}

// nativeKey 返回视频的稳定原生 key("provider:nativeID");无法解析时回退原串。
func (s *mediaServer) nativeKey(id string) string {
	if provider, nativeID, ok := s.resolveVideoRef(id); ok {
		return provider + ":" + nativeID
	}
	return strings.TrimSpace(id)
}

// nativeKeySet 把审校里配置的一组 ID 归一成稳定原生 key 集合,用于按原生 ID 匹配。
func (s *mediaServer) nativeKeySet(ids map[string]struct{}) map[string]struct{} {
	if len(ids) == 0 {
		return nil
	}
	out := make(map[string]struct{}, len(ids))
	for id := range ids {
		if key := s.nativeKey(id); key != "" {
			out[key] = struct{}{}
		}
	}
	return out
}

func curationTitleBlocked(item VideoSummary, terms []string) bool {
	if len(terms) == 0 {
		return false
	}
	text := strings.ToLower(item.Title + " " + item.Description)
	for _, term := range terms {
		if strings.Contains(text, term) {
			return true
		}
	}
	return false
}

// applyPins 把置顶视频按配置顺序挪到列表最前面;若某置顶 ID 不在聚合结果里,
// 则按 ID 主动拉取其摘要强制收录(拉取失败就跳过,不影响其余结果)。
// 列表项与置顶项都按稳定原生 key 比对,因此置顶项可用 provider:nativeID / 裸原生 ID 配置。
func (s *mediaServer) applyPins(ctx context.Context, pinIDs []string, items []VideoSummary) []VideoSummary {
	indexByID := make(map[string]int, len(items))
	indexByKey := make(map[string]int, len(items))
	for i, item := range items {
		indexByID[item.ID] = i
		if key := s.nativeKey(item.ID); key != "" {
			if _, exists := indexByKey[key]; !exists {
				indexByKey[key] = i
			}
		}
	}
	pinned := make([]VideoSummary, 0, len(pinIDs))
	usedItemIDs := make(map[string]struct{}, len(pinIDs))
	usedKeys := make(map[string]struct{}, len(pinIDs))
	for _, id := range pinIDs {
		key := s.nativeKey(id)
		if key != "" {
			if _, dup := usedKeys[key]; dup {
				continue
			}
		}
		if i, ok := indexByID[id]; ok {
			pinned = append(pinned, items[i])
			usedItemIDs[items[i].ID] = struct{}{}
			if key != "" {
				usedKeys[key] = struct{}{}
			}
			continue
		}
		if key != "" {
			if i, ok := indexByKey[key]; ok {
				pinned = append(pinned, items[i])
				usedItemIDs[items[i].ID] = struct{}{}
				usedKeys[key] = struct{}{}
				continue
			}
		}
		if summary, ok := s.summaryByID(ctx, id); ok {
			pinned = append(pinned, summary)
			usedItemIDs[summary.ID] = struct{}{}
			if key != "" {
				usedKeys[key] = struct{}{}
			}
		}
	}
	if len(pinned) == 0 {
		return items
	}
	rest := make([]VideoSummary, 0, len(items))
	for _, item := range items {
		if _, isPinned := usedItemIDs[item.ID]; isPinned {
			continue
		}
		rest = append(rest, item)
	}
	return append(pinned, rest...)
}

// summaryByID 按视频 ID 拉取其摘要(用于审校强制收录置顶项)。接受公开签名 ID /
// 裸原生 ID / provider:nativeID 三种写法;返回 raw 数据,delivery 重写在请求时统一处理。
func (s *mediaServer) summaryByID(ctx context.Context, id string) (VideoSummary, bool) {
	providerID, nativeID, ok := s.resolveVideoRef(id)
	if !ok {
		return VideoSummary{}, false
	}
	provider := s.providers[providerID]
	if provider == nil {
		return VideoSummary{}, false
	}
	details, err := provider.GetDetails(ctx, nativeID, s.idCodec)
	if err != nil {
		log.Printf("curation pin: fetch details failed id=%s: %v", id, err)
		return VideoSummary{}, false
	}
	return VideoSummary{
		ID:                details.ID,
		VideoKind:         details.VideoKind,
		Title:             details.Title,
		Description:       details.Description,
		Year:              details.Year,
		Creators:          details.Creators,
		ThumbnailURL:      details.ThumbnailURL,
		ThumbnailHeaders:  details.ThumbnailHeaders,
		ThumbnailDelivery: details.ThumbnailDelivery,
		DurationSeconds:   details.DurationSeconds,
		PlaybackKind:      details.PlaybackKind,
	}, true
}
