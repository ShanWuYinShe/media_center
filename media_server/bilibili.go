package main

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const (
	bilibiliWebOrigin = "https://www.bilibili.com"
	bilibiliUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

// BilibiliClient 是 bilibili provider 的最小实现:国内可直连源,server 端不再调
// B 站 view/playurl API,只返回 catalog 元数据 + client-resolve 占位 option,
// 让 App 端拿到 Provider + NativeID(=BVID)后自己解析播放地址。
// 保留 http.Client 字段是为了让 NewBilibiliClient 与 providers.go 期望的签名一致。
type BilibiliClient struct {
	client *http.Client
}

func NewBilibiliClient(timeout time.Duration) *BilibiliClient {
	return &BilibiliClient{
		client: &http.Client{
			Timeout:   timeout,
			Transport: newDirectUpstreamTransport(0, false),
		},
	}
}

func (c *BilibiliClient) ID() string {
	return "bilibili"
}

func (c *BilibiliClient) GetDetails(
	_ context.Context,
	bvid string,
	codec *IDCodec,
) (VideoDetails, error) {
	// 国内可直连源:server 端不再调 B 站 view API。handleVideos 会优先从 catalog
	// 拿元数据 + provider/nativeID,这里只是 catalog 未命中时的兜底:返回最小 details,
	// App 端拿到 Provider + NativeID 后自己解析播放地址。
	// PlaybackOptions 留空——App 端看到 provider=bilibili 就直接调本地 BilibiliApi,
	// 不会走 server /videos/{id}/playback。
	return VideoDetails{
		ID:           codec.EncodeVideoID(bvid),
		VideoKind:    "vod",
		Title:        bvid,
		PlaybackKind: "vod",
		Provider:     "bilibili",
		NativeID:     bvid,
	}, nil
}

func (c *BilibiliClient) GetPlaybackOptions(
	_ context.Context,
	_ string,
	_ *IDCodec,
) ([]PlaybackOption, error) {
	// 国内可直连源的 server 端不再调 B 站 playurl,PlaybackOptions 直接返回空。
	// App 端拿到 VideoSummary.provider=bilibili 后直接调本地 BilibiliApi,
	// 不会调到 server /videos/{id}/playback;此处仅作兜底。
	return []PlaybackOption{}, nil
}

// directThumbnailHeaders 返回抓取 B 站缩略图所需的 Referer(catalog_serve.go 透传给 App)。
func directThumbnailHeaders() map[string]string {
	return map[string]string{
		"Referer": bilibiliWebOrigin,
	}
}

// directPlaybackHeaders 返回 App 直连 B 站 CDN 播放流所需的请求头。
// 即便走 client-resolve 路径(App 自己拿 URL),仍需要 Referer/Origin 才能通过 B 站防盗链。
func directPlaybackHeaders() map[string]string {
	return map[string]string{
		"Referer": bilibiliWebOrigin,
		"Origin":  bilibiliWebOrigin,
	}
}

// qualityLabel 把 B 站清晰度代码(qn)转成展示标签。ids.go 的 EncodePlaybackID 用。
func qualityLabel(quality int) string {
	switch {
	case quality >= 120:
		return "4K"
	case quality >= 116:
		return "1080P60"
	case quality >= 112:
		return "1080P+"
	case quality >= 80:
		return "1080P"
	case quality >= 64:
		return "720P"
	case quality >= 32:
		return "480P"
	default:
		return "360P"
	}
}

// parseDuration 把 "HH:MM:SS" / "MM:SS" 字符串解析成秒。internet_archive.go 用。
func parseDuration(raw string) int64 {
	if raw == "" {
		return 0
	}
	parts := strings.Split(raw, ":")
	if len(parts) != 2 && len(parts) != 3 {
		return 0
	}
	total := int64(0)
	for _, part := range parts {
		var value int64
		if _, err := fmt.Sscanf(strings.TrimSpace(part), "%d", &value); err != nil {
			return 0
		}
		total = total*60 + value
	}
	return total
}

// creatorList 把单个作者名包装成切片(空名返回空切片)。多个 provider 共享。
func creatorList(name string) []string {
	if strings.TrimSpace(name) == "" {
		return []string{}
	}
	return []string{name}
}

// defaultString 取首个非空字符串。多个 provider 共享。
func defaultString(value, fallback string) string {
	if strings.TrimSpace(value) != "" {
		return strings.TrimSpace(value)
	}
	return fallback
}
