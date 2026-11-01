package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
)

// internalCollectionsRequest 是用全量快照替换某 provider 合集关系的请求体。
// 只写分组元数据(合集 ID/标题/有序 nativeId),不新增视频、不改审核状态。
type internalCollectionsRequest struct {
	Provider    string                   `json:"provider"`
	Collections []internalCollectionItem `json:"collections"`
}

type internalCollectionItem struct {
	ID       string   `json:"id"`
	Title    string   `json:"title,omitempty"`
	VideoIDs []string `json:"videoIds"`
}

// handleInternalCollections 用一次快照替换 provider 的全部合集关系。
// 供 scripts/sync-bilibili-collections.py 等采集工具调用(内部令牌鉴权)。
func (s *mediaServer) handleInternalCollections(w http.ResponseWriter, r *http.Request) {
	if s.catalog == nil {
		s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: "CATALOG_DISABLED", Message: "分类维护数据库未启用。"})
		return
	}
	var req internalCollectionsRequest
	// 快照可能包含多个千集合集:只发 nativeId 列表,16MB 足够;仍设上限
	// 防止把单连接 SQLite 写爆。
	if err := json.NewDecoder(io.LimitReader(r.Body, 16<<20)).Decode(&req); err != nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "请求体不是合法 JSON。"})
		return
	}
	provider := strings.TrimSpace(req.Provider)
	if provider == "" {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "provider 不能为空。"})
		return
	}
	if len(req.Collections) > 5000 {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "单次最多 5000 个合集。"})
		return
	}
	items := make([]CollectionImport, 0, len(req.Collections))
	for i, c := range req.Collections {
		id := strings.TrimSpace(c.ID)
		if id == "" {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("collections[%d].id 不能为空。", i)})
			return
		}
		if len(id) > 128 {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("collections[%d].id 过长。", i)})
			return
		}
		if len(c.VideoIDs) > 5000 {
			s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: fmt.Sprintf("collections[%d].videoIds 超过 5000 条。", i)})
			return
		}
		items = append(items, CollectionImport{ID: id, Title: c.Title, VideoIDs: c.VideoIDs})
	}
	collectionCount, memberCount, err := s.catalog.ReplaceCollections(provider, items)
	if err != nil {
		log.Printf("replace collections provider=%s: %v", provider, err)
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "合集写入失败。"})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"provider":    provider,
		"collections": collectionCount,
		"members":     memberCount,
	})
}
