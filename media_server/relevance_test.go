package main

import "testing"

func TestRelevanceScoreRejectsShortNonDotaVideo(t *testing.T) {
	score := relevanceScore(
		VideoSummary{
			Title:           "电影完整版",
			Description:     "老电影 正片",
			Creators:        []string{"频道"},
			DurationSeconds: 10 * 60,
		},
		ContentCategory{
			ID:       "movies",
			Keywords: []string{"电影", "完整版"},
		},
	)
	if score >= 0 {
		t.Fatalf("expected short movie candidate to be rejected, got %d", score)
	}
}

func TestDefaultFiltersAvoidBroadGameFalsePositives(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}

	movies := mustCategory(t, cfg.Categories, "movies")
	movieScore := relevanceScore(
		VideoSummary{
			Title:           "饥饿游戏 完整版 正片",
			Description:     "高清电影",
			DurationSeconds: 2 * 60 * 60,
		},
		movies,
	)
	if movieScore < 0 {
		t.Fatalf("expected movie title containing 游戏 to pass, got %d", movieScore)
	}

	tvSeries := mustCategory(t, cfg.Categories, "tv-series")
	tvScore := relevanceScore(
		VideoSummary{
			Title:           "鱿鱼游戏 第1集",
			Description:     "电视剧 剧集",
			DurationSeconds: 60 * 60,
		},
		tvSeries,
	)
	if tvScore < 0 {
		t.Fatalf("expected tv title containing 游戏 to pass, got %d", tvScore)
	}
}

func TestDefaultDotaFiltersRejectHighlightNoise(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	dota := mustCategory(t, cfg.Categories, "dota-zone")

	score := relevanceScore(
		VideoSummary{
			Title:           "Dota1 TOP10 集锦 高光",
			Description:     "精彩操作剪辑",
			DurationSeconds: 10 * 60,
		},
		dota,
	)
	if score >= 0 {
		t.Fatalf("expected dota highlight noise to be rejected, got %d", score)
	}
}

func mustCategory(t *testing.T, categories []ContentCategory, id string) ContentCategory {
	t.Helper()
	for _, category := range categories {
		if category.ID == id {
			return category
		}
	}
	t.Fatalf("category %q not found", id)
	return ContentCategory{}
}
