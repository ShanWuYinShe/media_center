package main

// summaryFromCatalogVideo 把一行 CatalogVideo 转成运行时 VideoSummary。
// server 端只做目录服务:id 直接用 catalog 的 nativeId(如 BV1xxx),
// 不做签名编码;缩略图用原始 URL,App 端自己处理 Referer 等请求头。
func (s *mediaServer) summaryFromCatalogVideo(cv CatalogVideo) VideoSummary {
	return VideoSummary{
		ID:              cv.NativeID,
		Title:           cv.Title,
		Description:     cv.Description,
		ThumbnailURL:    cv.ThumbnailURL,
		DurationSeconds: cv.DurationSec,
	}
}
