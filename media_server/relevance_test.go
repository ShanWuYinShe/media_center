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
