package main

import (
	"context"
	"fmt"
	"testing"
)

// fakeProvider returns a fixed per-query Total on every page (as real search
// APIs do: NumResults / totalResults are query-wide, identical across pages).
type fakeProvider struct {
	id           string
	total        int
	itemsPerPage int
}

func (f *fakeProvider) ID() string { return f.id }

func (f *fakeProvider) Search(_ context.Context, query string, page, pageSize int, _ *IDCodec) (SearchResponse, error) {
	items := make([]VideoSummary, 0, f.itemsPerPage)
	for i := 0; i < f.itemsPerPage; i++ {
		items = append(items, VideoSummary{
			ID:              fmt.Sprintf("%s-p%d-i%d", f.id, page, i),
			Title:           "dota highlight",
			DurationSeconds: 1800,
			PlaybackKind:    "vod",
		})
	}
	return SearchResponse{Query: query, Page: page, PageSize: pageSize, Total: f.total, Items: items}, nil
}

func (f *fakeProvider) GetDetails(_ context.Context, _ string, _ *IDCodec) (VideoDetails, error) {
	return VideoDetails{}, nil
}

func (f *fakeProvider) GetPlaybackOptions(_ context.Context, _ string, _ *IDCodec) ([]PlaybackOption, error) {
	return nil, nil
}

// collectCategoryPage scans multiple provider pages. The provider's Total is a
// query-wide count repeated on every page, so it must be counted once per
// provider — not added per page (which inflated Total by the page count and
// broke client pagination).
func TestCollectCategoryPageTotalNotInflatedAcrossPages(t *testing.T) {
	const perQueryTotal = 1000
	fp := &fakeProvider{id: "fake", total: perQueryTotal, itemsPerPage: 5}
	s := &mediaServer{
		config:         AppConfig{MaxCategoryPages: 3},
		providers:      map[string]VideoProvider{"fake": fp},
		enabledSources: []string{"fake"},
		idCodec:        NewIDCodec("test-secret"),
	}
	cat := ContentCategory{
		ID:       "general",
		Title:    "General",
		Query:    "dota",
		Keywords: []string{"dota"},
	}

	resp, err := s.collectCategoryPage(context.Background(), cat, 1, 20)
	if err != nil {
		t.Fatalf("collectCategoryPage: %v", err)
	}
	// 3 pages * 5 relevant items each = 15 collected.
	if len(resp.Items) != 15 {
		t.Fatalf("expected 15 collected items, got %d", len(resp.Items))
	}
	if resp.Total != perQueryTotal {
		t.Errorf("Total inflated across pages: got %d, want %d", resp.Total, perQueryTotal)
	}
}
