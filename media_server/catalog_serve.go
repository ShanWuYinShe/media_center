package main

import "strings"

// summaryFromCatalogVideo 把一行 CatalogVideo 转成运行时 VideoSummary(原始字段,
// 不做 delivery 重写——签名/代理仍在每次请求时统一处理)。
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

// providerThumbnailHeaders 返回某 provider 抓取缩略图所需的直连请求头(如 bilibili 的 Referer)。
func providerThumbnailHeaders(provider string) map[string]string {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "bilibili":
		return directThumbnailHeaders()
	default:
		return nil
	}
}
