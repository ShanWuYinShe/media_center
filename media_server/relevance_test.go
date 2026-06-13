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

func TestDefaultDotaFiltersAllowHighlightCompilation(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	dota := mustCategory(t, cfg.Categories, "dota-zone")

	// 用户明确要 dota1 的精彩集锦,只要时长达标就应保留。
	score := relevanceScore(
		VideoSummary{
			Title:           "Dota1 路人局精彩集锦",
			Description:     "经典对局集锦",
			DurationSeconds: 12 * 60,
		},
		dota,
	)
	if score < 0 {
		t.Fatalf("expected dota1 highlight compilation to be kept, got %d", score)
	}
}

func TestDefaultDotaFiltersKeepCommentary(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	dota := mustCategory(t, cfg.Categories, "dota-zone")

	score := relevanceScore(
		VideoSummary{
			Title:           "Dota1 经典解说 完整对局",
			Description:     "主播解说",
			DurationSeconds: 45 * 60,
		},
		dota,
	)
	if score < 0 {
		t.Fatalf("expected dota1 commentary to be kept, got %d", score)
	}
}

func TestDefaultDotaFiltersRejectShortVideos(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	dota := mustCategory(t, cfg.Categories, "dota-zone")

	score := relevanceScore(
		VideoSummary{
			Title:           "dota冷冷透露为什么不再打dota2，也不打dota1了",
			Description:     "Dota 相关短视频",
			DurationSeconds: 85,
		},
		dota,
	)
	if score >= 0 {
		t.Fatalf("expected short dota candidate to be rejected, got %d", score)
	}
}

func TestMoviesFilterRejectsAdultContent(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	movies := mustCategory(t, cfg.Categories, "movies")

	score := relevanceScore(
		VideoSummary{
			Title:           "某三级片 完整版 正片",
			Description:     "限制级 情色电影",
			DurationSeconds: 90 * 60,
		},
		movies,
	)
	if score >= 0 {
		t.Fatalf("expected adult movie to be rejected, got %d", score)
	}
}

func TestMoviesFilterKeepsFamilyFriendlyFilm(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	movies := mustCategory(t, cfg.Categories, "movies")

	score := relevanceScore(
		VideoSummary{
			Title:           "经典电影 完整版 正片",
			Description:     "高清国语",
			DurationSeconds: 110 * 60,
		},
		movies,
	)
	if score < 0 {
		t.Fatalf("expected family-friendly film to be kept, got %d", score)
	}
}

func TestFamilyTheaterFilter(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	family := mustCategory(t, cfg.Categories, "family-theater")

	kept := relevanceScore(
		VideoSummary{
			Title:           "都市家庭生活剧 全集",
			Description:     "婆媳 家长里短",
			DurationSeconds: 45 * 60,
		},
		family,
	)
	if kept < 0 {
		t.Fatalf("expected family drama to be kept, got %d", kept)
	}

	// 命中家庭主题但不是剧集(电影)应被排除,避免和电影分类混淆。
	notSeries := relevanceScore(
		VideoSummary{
			Title:           "家庭温情电影 完整版",
			Description:     "都市生活",
			DurationSeconds: 100 * 60,
		},
		family,
	)
	if notSeries >= 0 {
		t.Fatalf("expected non-series family film to be rejected, got %d", notSeries)
	}
}

func TestRedClassicFilterCoversModernHistory(t *testing.T) {
	cfg, err := loadContentConfig("src/contentCategories.json")
	if err != nil {
		t.Fatalf("load content config: %v", err)
	}
	red := mustCategory(t, cfg.Categories, "red-classic")

	for _, tc := range []struct {
		name string
		item VideoSummary
	}{
		{"对越自卫反击", VideoSummary{Title: "对越自卫反击战 纪录片", Description: "历史纪实", DurationSeconds: 50 * 60}},
		{"辛亥革命", VideoSummary{Title: "辛亥革命 纪录片", Description: "革命历史", DurationSeconds: 60 * 60}},
		{"长征剧", VideoSummary{Title: "长征 革命历史 老电影", Description: "红军", DurationSeconds: 90 * 60}},
	} {
		if score := relevanceScore(tc.item, red); score < 0 {
			t.Fatalf("expected red-classic %s to be kept, got %d", tc.name, score)
		}
	}

	// 与中国无关的外国历史(如二战德国)不应进入“红色经典”。
	foreign := relevanceScore(
		VideoSummary{
			Title:           "1933年希特勒就职演讲完整版",
			Description:     "二战 德国 历史 纪录片",
			DurationSeconds: 30 * 60,
		},
		red,
	)
	if foreign >= 0 {
		t.Fatalf("expected foreign history to be rejected from red-classic, got %d", foreign)
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
