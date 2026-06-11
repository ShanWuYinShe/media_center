package main

import (
	"context"
	"fmt"
)

type VideoProvider interface {
	ID() string
	Search(ctx context.Context, query string, page int, pageSize int, codec *IDCodec) (SearchResponse, error)
	GetDetails(ctx context.Context, id string, codec *IDCodec) (VideoDetails, error)
	GetPlaybackOptions(ctx context.Context, id string, codec *IDCodec) ([]PlaybackOption, error)
}

func newVideoProviders(cfg AppConfig, sources []sourceConfig) (map[string]VideoProvider, []string, error) {
	if len(sources) == 0 {
		sources = []sourceConfig{{ID: "bilibili", Enabled: true}}
	}

	enabled := make([]string, 0, len(sources))
	for _, source := range sources {
		if source.Enabled {
			enabled = append(enabled, source.ID)
		}
	}
	if len(enabled) == 0 {
		return nil, nil, fmt.Errorf("contentCategories.json must enable at least one source")
	}

	providers := map[string]VideoProvider{
		"bilibili":         NewBilibiliClient(cfg.ProviderTimeout),
		"internet-archive": NewInternetArchiveClient(cfg.ProviderTimeout),
	}
	for _, id := range enabled {
		switch id {
		case "bilibili", "internet-archive":
		case "youtube":
			if cfg.YouTubeAPIKey == "" {
				return nil, nil, fmt.Errorf("youtube source requires YOUTUBE_API_KEY")
			}
			if cfg.VODExtractorURL == "" {
				return nil, nil, fmt.Errorf("youtube source requires VOD_EXTRACTOR_URL")
			}
			providers["youtube"] = NewYouTubeClient(cfg.ProviderTimeout, cfg.YouTubeAPIKey, cfg.VODExtractorURL)
		default:
			return nil, nil, fmt.Errorf("unsupported media source: %s", id)
		}
	}
	return providers, enabled, nil
}
