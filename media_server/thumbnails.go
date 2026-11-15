package main

import (
	"net/url"
	"strings"
)

// 缩略图尺寸变体:目录里的 B 站封面是原始大图(实测单张可达 600KB+),而 10ft
// 电视卡片只需要 ~640px 宽。B 站 CDN 支持 `<url>@<w>w_<h>h_1c.webp` 变体
// (中心裁切 + webp;实测 640x400 仅 ~9KB、1280x720 ~20KB),服务端直接下发
// 卡片/详情两个尺寸,App 与 legacy 无需改请求逻辑,弱网首屏带宽降 1~2 个数量级。
const (
	bilibiliCardThumbSuffix  = "@640w_400h_1c.webp"  // 16:10,首页/搜索卡片
	bilibiliLargeThumbSuffix = "@1280w_720h_1c.webp" // 16:9,详情页封面/背景
)

// thumbnailVariants 返回 (卡片尺寸, 详情尺寸)。未知图床原样返回两张相同 URL,
// 保持既有行为;调用方负责先升 HTTPS。
func thumbnailVariants(raw string) (card string, large string) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", ""
	}
	if !isBilibiliImageURL(trimmed) {
		return trimmed, trimmed
	}
	base := stripCDNSuffix(trimmed)
	return base + bilibiliCardThumbSuffix, base + bilibiliLargeThumbSuffix
}

// isBilibiliImageURL 只认 B 站图床域名,避免给任意站点 URL 拼上 B 站 CDN 后缀。
func isBilibiliImageURL(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	return host == "hdslb.com" || strings.HasSuffix(host, ".hdslb.com")
}

// stripCDNSuffix 去掉已存在的 `@...` 处理参数,避免重复追加。
func stripCDNSuffix(raw string) string {
	if index := strings.LastIndex(raw, "@"); index > 0 {
		return raw[:index]
	}
	return raw
}

// youtubeCardThumbnail 从 yt-dlp flat 条目的缩略图列表里挑卡片尺寸:
// 取宽度 >= 480 的最小一档(yt-dlp 常见 mqdefault 320 / hqdefault 480 / maxres 1280);
// 没有 >=480 时退回最大一档。详情页仍用 bestThumbnailURL 的最高分辨率。
func youtubeCardThumbnail(e ytdlpEntry) string {
	best := ""
	bestWidth := -1
	largest := ""
	largestWidth := -1
	for _, thumbnail := range e.Thumbnails {
		if thumbnail.URL == "" {
			continue
		}
		if thumbnail.Width >= 480 && (bestWidth < 0 || thumbnail.Width < bestWidth) {
			best, bestWidth = thumbnail.URL, thumbnail.Width
		}
		if thumbnail.Width >= largestWidth {
			largest, largestWidth = thumbnail.URL, thumbnail.Width
		}
	}
	if best != "" {
		return best
	}
	if largest != "" {
		return largest
	}
	return e.Thumbnail
}
