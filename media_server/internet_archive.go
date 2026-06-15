package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"path"
	"slices"
	"strconv"
	"strings"
	"time"
)

const internetArchiveBase = "https://archive.org"

type InternetArchiveClient struct {
	client *http.Client
}

type iaMetadataResponse struct {
	Metadata struct {
		Identifier  string `json:"identifier"`
		Title       any    `json:"title"`
		Description any    `json:"description"`
		Creator     any    `json:"creator"`
		Year        any    `json:"year"`
		Date        any    `json:"date"`
	} `json:"metadata"`
	Files []iaFile `json:"files"`
}

type iaFile struct {
	Name   string `json:"name"`
	Format string `json:"format"`
	Title  string `json:"title"`
	Length any    `json:"length"`
	Size   any    `json:"size"`
}

func NewInternetArchiveClient(timeout time.Duration) *InternetArchiveClient {
	return &InternetArchiveClient{client: &http.Client{Timeout: timeout}}
}

func (c *InternetArchiveClient) ID() string {
	return "internet-archive"
}

func (c *InternetArchiveClient) GetDetails(ctx context.Context, id string, codec *IDCodec) (VideoDetails, error) {
	meta, err := c.fetchMetadata(ctx, id)
	if err != nil {
		return VideoDetails{}, err
	}
	options := iaPlaybackOptions(c.ID(), id, meta.Files, codec)
	if len(options) == 0 {
		return VideoDetails{}, &apiError{
			Status:  http.StatusUnprocessableEntity,
			Code:    "NO_PLAYABLE_STREAM",
			Message: "该 Internet Archive 条目暂无可播放点播文件。",
		}
	}
	return VideoDetails{
		ID:              codec.EncodeProviderVideoID(c.ID(), id),
		VideoKind:       "vod",
		Title:           defaultString(firstIAString(meta.Metadata.Title), id),
		Description:     firstIAString(meta.Metadata.Description),
		Year:            iaYear(meta.Metadata.Year, meta.Metadata.Date),
		Creators:        iaCreators(meta.Metadata.Creator),
		ThumbnailURL:    internetArchiveBase + "/services/img/" + url.PathEscape(id),
		DurationSeconds: iaFirstDuration(meta.Files),
		PlaybackKind:    "vod",
		PlaybackOptions: options,
	}, nil
}

func (c *InternetArchiveClient) GetPlaybackOptions(ctx context.Context, id string, codec *IDCodec) ([]PlaybackOption, error) {
	meta, err := c.fetchMetadata(ctx, id)
	if err != nil {
		return nil, err
	}
	options := iaPlaybackOptions(c.ID(), id, meta.Files, codec)
	if len(options) == 0 {
		return nil, &apiError{
			Status:  http.StatusUnprocessableEntity,
			Code:    "NO_PLAYABLE_STREAM",
			Message: "该 Internet Archive 条目暂无可播放点播文件。",
		}
	}
	return options, nil
}

func (c *InternetArchiveClient) fetchMetadata(ctx context.Context, id string) (iaMetadataResponse, error) {
	if strings.TrimSpace(id) == "" || strings.Contains(id, "/") {
		return iaMetadataResponse{}, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "视频 ID 不合法。"}
	}
	var payload iaMetadataResponse
	if err := c.getJSON(ctx, internetArchiveBase+"/metadata/"+url.PathEscape(id), &payload); err != nil {
		return iaMetadataResponse{}, err
	}
	if payload.Metadata.Identifier == "" {
		payload.Metadata.Identifier = id
	}
	return payload, nil
}

func (c *InternetArchiveClient) getJSON(ctx context.Context, rawURL string, target any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "workwork-media-server/1.0")
	resp, err := c.client.Do(req)
	if err != nil {
		return &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "无法连接 Internet Archive API。"}
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return &apiError{Status: http.StatusNotFound, Code: "NOT_FOUND", Message: "未找到视频详情。"}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &apiError{Status: http.StatusBadGateway, Code: "UPSTREAM_UNAVAILABLE", Message: "Internet Archive API 返回了错误响应。"}
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

func iaPlaybackOptions(provider, identifier string, files []iaFile, codec *IDCodec) []PlaybackOption {
	candidates := make([]iaFile, 0, len(files))
	for _, file := range files {
		ext := strings.ToLower(path.Ext(file.Name))
		if file.Name == "" || strings.Contains(file.Name, "/") {
			continue
		}
		if ext == ".mp4" || ext == ".m4v" || ext == ".webm" {
			candidates = append(candidates, file)
		}
	}
	slices.SortFunc(candidates, func(a, b iaFile) int {
		return int64Compare(iaFileSize(b), iaFileSize(a))
	})
	if len(candidates) > 4 {
		candidates = candidates[:4]
	}
	options := make([]PlaybackOption, 0, len(candidates))
	seen := map[string]struct{}{}
	for _, file := range candidates {
		ext := strings.ToLower(path.Ext(file.Name))
		label := strings.TrimSpace(file.Title)
		if label == "" {
			label = strings.TrimPrefix(strings.ToUpper(strings.TrimPrefix(ext, ".")), ".")
		}
		if label == "" {
			label = file.Name
		}
		if _, ok := seen[label]; ok {
			label = file.Name
		}
		seen[label] = struct{}{}
		options = append(options, PlaybackOption{
			ID:       codec.EncodeProviderPlaybackID(provider, identifier, file.Name, 0),
			Kind:     "vod",
			Label:    label,
			URL:      internetArchiveBase + "/download/" + url.PathEscape(identifier) + "/" + url.PathEscape(file.Name),
			MimeType: iaMimeType(ext),
		})
	}
	return options
}

func iaFirstDuration(files []iaFile) int64 {
	for _, file := range files {
		if d := iaDuration(file.Length); d > 0 {
			return d
		}
	}
	return 0
}

func iaDuration(value any) int64 {
	switch v := value.(type) {
	case string:
		if seconds, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil {
			return int64(seconds)
		}
		return parseDuration(v)
	case float64:
		return int64(v)
	default:
		return 0
	}
}

func iaFileSize(file iaFile) int64 {
	switch v := file.Size.(type) {
	case string:
		n, _ := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
		return n
	case float64:
		return int64(v)
	default:
		return 0
	}
}

func iaMimeType(ext string) string {
	switch ext {
	case ".webm":
		return "video/webm"
	default:
		return "video/mp4"
	}
}

func firstIAString(value any) string {
	switch v := value.(type) {
	case string:
		return strings.TrimSpace(v)
	case []any:
		for _, item := range v {
			if s := firstIAString(item); s != "" {
				return s
			}
		}
	case []string:
		for _, item := range v {
			if s := strings.TrimSpace(item); s != "" {
				return s
			}
		}
	}
	return ""
}

func iaCreators(value any) []string {
	switch v := value.(type) {
	case string:
		return creatorList(v)
	case []any:
		out := make([]string, 0, len(v))
		for _, item := range v {
			if s := firstIAString(item); s != "" {
				out = append(out, s)
			}
		}
		return out
	case []string:
		out := make([]string, 0, len(v))
		for _, item := range v {
			if s := strings.TrimSpace(item); s != "" {
				out = append(out, s)
			}
		}
		return out
	default:
		return []string{}
	}
}

func iaYear(yearValue any, dateValue any) string {
	if year := firstIAString(yearValue); len(year) >= 4 {
		return year[:4]
	}
	if date := firstIAString(dateValue); len(date) >= 4 {
		return date[:4]
	}
	return ""
}

func int64Compare(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}
