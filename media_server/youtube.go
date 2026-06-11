package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

const youtubeAPIBase = "https://www.googleapis.com/youtube/v3"

var youtubeDurationPattern = regexp.MustCompile(`^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$`)

type YouTubeClient struct {
	client       *http.Client
	apiKey       string
	extractorURL string
}

type youtubeSearchResponse struct {
	NextPageToken string `json:"nextPageToken"`
	PageInfo      struct {
		TotalResults int `json:"totalResults"`
	} `json:"pageInfo"`
	Items []struct {
		ID struct {
			VideoID string `json:"videoId"`
		} `json:"id"`
		Snippet youtubeSnippet `json:"snippet"`
	} `json:"items"`
}

type youtubeVideosResponse struct {
	Items []struct {
		ID             string         `json:"id"`
		Snippet        youtubeSnippet `json:"snippet"`
		ContentDetails struct {
			Duration string `json:"duration"`
		} `json:"contentDetails"`
	} `json:"items"`
}

type youtubeSnippet struct {
	Title        string `json:"title"`
	Description  string `json:"description"`
	ChannelTitle string `json:"channelTitle"`
	PublishedAt  string `json:"publishedAt"`
	Thumbnails   struct {
		Maxres   youtubeThumbnail `json:"maxres"`
		High     youtubeThumbnail `json:"high"`
		Medium   youtubeThumbnail `json:"medium"`
		Standard youtubeThumbnail `json:"standard"`
		Default  youtubeThumbnail `json:"default"`
	} `json:"thumbnails"`
}

type youtubeThumbnail struct {
	URL string `json:"url"`
}

type extractorPayload struct {
	ID          string            `json:"id"`
	Title       string            `json:"title"`
	Description string            `json:"description"`
	Uploader    string            `json:"uploader"`
	Channel     string            `json:"channel"`
	Thumbnail   string            `json:"thumbnail"`
	Duration    float64           `json:"duration"`
	URL         string            `json:"url"`
	Ext         string            `json:"ext"`
	HTTPHeaders map[string]string `json:"http_headers"`
	Formats     []ytDLPFormat     `json:"formats"`
}

type ytDLPFormat struct {
	FormatID       string            `json:"format_id"`
	Format         string            `json:"format"`
	FormatNote     string            `json:"format_note"`
	Ext            string            `json:"ext"`
	Protocol       string            `json:"protocol"`
	URL            string            `json:"url"`
	VCodec         string            `json:"vcodec"`
	ACodec         string            `json:"acodec"`
	Height         int               `json:"height"`
	TBR            float64           `json:"tbr"`
	Filesize       int64             `json:"filesize"`
	FilesizeApprox int64             `json:"filesize_approx"`
	HTTPHeaders    map[string]string `json:"http_headers"`
}

func NewYouTubeClient(timeout time.Duration, apiKey, extractorURL string) *YouTubeClient {
	return &YouTubeClient{
		client:       &http.Client{Timeout: timeout},
		apiKey:       apiKey,
		extractorURL: extractorURL,
	}
}

func (c *YouTubeClient) ID() string {
	return "youtube"
}

func (c *YouTubeClient) Search(
	ctx context.Context,
	query string,
	page int,
	pageSize int,
	codec *IDCodec,
) (SearchResponse, error) {
	payload, err := c.searchPage(ctx, query, page, min(pageSize, 50))
	if err != nil {
		return SearchResponse{}, err
	}
	items := make([]VideoSummary, 0, len(payload.Items))
	for _, item := range payload.Items {
		videoID := strings.TrimSpace(item.ID.VideoID)
		if videoID == "" {
			continue
		}
		items = append(items, youtubeSummary(c.ID(), videoID, item.Snippet, 0, codec))
	}
	total := payload.PageInfo.TotalResults
	if total <= 0 {
		total = len(items)
	}
	return SearchResponse{Query: query, Page: page, PageSize: pageSize, Total: total, Items: items}, nil
}

func (c *YouTubeClient) GetDetails(ctx context.Context, id string, codec *IDCodec) (VideoDetails, error) {
	item, err := c.fetchVideo(ctx, id)
	if err != nil {
		return VideoDetails{}, err
	}
	options, err := c.GetPlaybackOptions(ctx, id, codec)
	if err != nil {
		return VideoDetails{}, err
	}
	summary := youtubeSummary(c.ID(), item.ID, item.Snippet, parseYouTubeDuration(item.ContentDetails.Duration), codec)
	return VideoDetails{
		ID:              summary.ID,
		VideoKind:       summary.VideoKind,
		Title:           summary.Title,
		Description:     summary.Description,
		Year:            summary.Year,
		Creators:        summary.Creators,
		ThumbnailURL:    summary.ThumbnailURL,
		DurationSeconds: summary.DurationSeconds,
		PlaybackKind:    summary.PlaybackKind,
		PlaybackOptions: options,
	}, nil
}

func (c *YouTubeClient) GetPlaybackOptions(ctx context.Context, id string, codec *IDCodec) ([]PlaybackOption, error) {
	payload, err := c.extract(ctx, "https://www.youtube.com/watch?v="+url.QueryEscape(id))
	if err != nil {
		return nil, err
	}
	options := youtubePlaybackOptions(c.ID(), id, payload, codec)
	if len(options) == 0 {
		return nil, &apiError{
			Status:  http.StatusUnprocessableEntity,
			Code:    "NO_PLAYABLE_STREAM",
			Message: "该 YouTube 视频暂无可播放点播流。",
		}
	}
	return options, nil
}

func (c *YouTubeClient) searchPage(ctx context.Context, query string, page int, pageSize int) (youtubeSearchResponse, error) {
	token := ""
	var payload youtubeSearchResponse
	for currentPage := 1; currentPage <= page; currentPage++ {
		u, _ := url.Parse(youtubeAPIBase + "/search")
		q := u.Query()
		q.Set("key", c.apiKey)
		q.Set("part", "snippet")
		q.Set("type", "video")
		q.Set("videoEmbeddable", "true")
		q.Set("safeSearch", "moderate")
		q.Set("maxResults", strconv.Itoa(pageSize))
		q.Set("q", query)
		if token != "" {
			q.Set("pageToken", token)
		}
		u.RawQuery = q.Encode()
		if err := c.getJSON(ctx, u.String(), &payload); err != nil {
			return youtubeSearchResponse{}, err
		}
		if currentPage == page {
			return payload, nil
		}
		token = payload.NextPageToken
		if token == "" {
			return youtubeSearchResponse{PageInfo: payload.PageInfo}, nil
		}
	}
	return payload, nil
}

func (c *YouTubeClient) fetchVideo(ctx context.Context, id string) (struct {
	ID             string         `json:"id"`
	Snippet        youtubeSnippet `json:"snippet"`
	ContentDetails struct {
		Duration string `json:"duration"`
	} `json:"contentDetails"`
}, error) {
	var zero struct {
		ID             string         `json:"id"`
		Snippet        youtubeSnippet `json:"snippet"`
		ContentDetails struct {
			Duration string `json:"duration"`
		} `json:"contentDetails"`
	}
	u, _ := url.Parse(youtubeAPIBase + "/videos")
	q := u.Query()
	q.Set("key", c.apiKey)
	q.Set("part", "snippet,contentDetails")
	q.Set("id", id)
	u.RawQuery = q.Encode()
	var payload youtubeVideosResponse
	if err := c.getJSON(ctx, u.String(), &payload); err != nil {
		return zero, err
	}
	if len(payload.Items) == 0 {
		return zero, &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到视频详情。"}
	}
	return payload.Items[0], nil
}

func (c *YouTubeClient) getJSON(ctx context.Context, rawURL string, target any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "workwork-media-server/1.0")
	resp, err := c.client.Do(req)
	if err != nil {
		return &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "无法连接 YouTube API。"}
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "YouTube API 返回了错误响应。"}
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(body, target); err != nil {
		return err
	}
	return nil
}

func (c *YouTubeClient) extract(ctx context.Context, videoURL string) (extractorPayload, error) {
	body, _ := json.Marshal(map[string]string{"url": videoURL})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.extractorURL, bytes.NewReader(body))
	if err != nil {
		return extractorPayload{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.client.Do(req)
	if err != nil {
		return extractorPayload{}, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "无法连接点播解析服务。"}
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode == http.StatusServiceUnavailable {
		return extractorPayload{}, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "点播解析服务繁忙，请稍后重试。"}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return extractorPayload{}, &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "点播解析服务返回了错误响应。"}
	}
	limited := io.LimitReader(resp.Body, 20*1024*1024)
	var payload extractorPayload
	if err := json.NewDecoder(limited).Decode(&payload); err != nil {
		return extractorPayload{}, err
	}
	return payload, nil
}

func youtubeSummary(provider, id string, snippet youtubeSnippet, duration int64, codec *IDCodec) VideoSummary {
	return VideoSummary{
		ID:              codec.EncodeProviderVideoID(provider, id),
		VideoKind:       "vod",
		Title:           defaultString(strings.TrimSpace(snippet.Title), id),
		Description:     snippet.Description,
		Year:            youtubeYear(snippet.PublishedAt),
		Creators:        creatorList(snippet.ChannelTitle),
		ThumbnailURL:    bestYouTubeThumbnail(snippet),
		DurationSeconds: duration,
		PlaybackKind:    "vod",
	}
}

func youtubePlaybackOptions(provider, id string, payload extractorPayload, codec *IDCodec) []PlaybackOption {
	formats := make([]ytDLPFormat, 0, len(payload.Formats))
	for _, format := range payload.Formats {
		if !isHTTPMediaURL(format.URL) || strings.EqualFold(format.VCodec, "none") || strings.EqualFold(format.ACodec, "none") {
			continue
		}
		formats = append(formats, format)
	}
	slices.SortFunc(formats, func(a, b ytDLPFormat) int {
		if a.Height != b.Height {
			return b.Height - a.Height
		}
		switch {
		case a.TBR < b.TBR:
			return 1
		case a.TBR > b.TBR:
			return -1
		default:
			return 0
		}
	})
	if len(formats) > 4 {
		formats = formats[:4]
	}
	options := make([]PlaybackOption, 0, len(formats)+1)
	seen := map[string]struct{}{}
	for _, format := range formats {
		label := youtubeFormatLabel(format)
		if _, ok := seen[label]; ok {
			continue
		}
		seen[label] = struct{}{}
		options = append(options, PlaybackOption{
			ID:       codec.EncodeProviderPlaybackID(provider, id, format.FormatID, format.Height),
			Kind:     "vod",
			Label:    label,
			URL:      format.URL,
			MimeType: youtubeMimeType(format.Ext, format.Protocol),
			Headers:  firstHeaderMap(format.HTTPHeaders, payload.HTTPHeaders),
		})
	}
	if len(options) == 0 && isHTTPMediaURL(payload.URL) {
		options = append(options, PlaybackOption{
			ID:       codec.EncodeProviderPlaybackID(provider, id, "default", 0),
			Kind:     "vod",
			Label:    "Default",
			URL:      payload.URL,
			MimeType: youtubeMimeType(payload.Ext, ""),
			Headers:  payload.HTTPHeaders,
		})
	}
	return options
}

func youtubeFormatLabel(format ytDLPFormat) string {
	if format.FormatNote != "" {
		return format.FormatNote
	}
	if format.Height > 0 {
		return fmt.Sprintf("%dP", format.Height)
	}
	if format.Format != "" {
		return format.Format
	}
	return defaultString(strings.ToUpper(format.Ext), "Default")
}

func youtubeMimeType(ext, protocol string) string {
	if strings.Contains(protocol, "m3u8") {
		return "application/x-mpegURL"
	}
	switch strings.ToLower(ext) {
	case "webm":
		return "video/webm"
	default:
		return "video/mp4"
	}
}

func bestYouTubeThumbnail(snippet youtubeSnippet) string {
	for _, thumbnail := range []youtubeThumbnail{
		snippet.Thumbnails.Maxres,
		snippet.Thumbnails.Standard,
		snippet.Thumbnails.High,
		snippet.Thumbnails.Medium,
		snippet.Thumbnails.Default,
	} {
		if strings.TrimSpace(thumbnail.URL) != "" {
			return thumbnail.URL
		}
	}
	return ""
}

func parseYouTubeDuration(value string) int64 {
	m := youtubeDurationPattern.FindStringSubmatch(value)
	if m == nil {
		return 0
	}
	days := atoi64(m[1])
	hours := atoi64(m[2])
	minutes := atoi64(m[3])
	seconds := atoi64(m[4])
	return days*24*3600 + hours*3600 + minutes*60 + seconds
}

func youtubeYear(value string) string {
	if len(value) >= 4 {
		return value[:4]
	}
	return ""
}

func isHTTPMediaURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != ""
}

func firstHeaderMap(values ...map[string]string) map[string]string {
	for _, value := range values {
		if len(value) > 0 {
			return value
		}
	}
	return nil
}

func atoi64(value string) int64 {
	if value == "" {
		return 0
	}
	n, _ := strconv.ParseInt(value, 10, 64)
	return n
}
