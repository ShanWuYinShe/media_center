package main

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
)

const (
	bilibiliAPIBase   = "https://api.bilibili.com"
	bilibiliWebOrigin = "https://www.bilibili.com"
	bilibiliUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

var (
	htmlTagPattern      = regexp.MustCompile(`<[^>]*>`)
	qualityDescPattern  = regexp.MustCompile(`(\d+\s*[PpKk][^\s]*|4\s*[Kk])`)
	wbiSanitizeReplacer = strings.NewReplacer("!", "", "'", "", "(", "", ")", "", "*", "")
)

var mixinKeyEncTab = []int{
	46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
	33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
	26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
	20, 34, 44, 52,
}

var commentaryKeywords = []string{
	"解说", "盘点", "速看", "影评", "混剪", "分钟看", "预告", "花絮", "片段", "几分钟看",
}

type BilibiliClient struct {
	client    *http.Client
	keysMu    sync.Mutex
	imgKey    string
	subKey    string
	keyExpiry time.Time
}

type bilibiliSearchResponse struct {
	Code int `json:"code"`
	Data struct {
		NumResults int                  `json:"numResults"`
		Result     []bilibiliSearchItem `json:"result"`
	} `json:"data"`
}

type bilibiliSearchItem struct {
	BVID        string `json:"bvid"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Author      string `json:"author"`
	Pic         string `json:"pic"`
	Duration    string `json:"duration"`
	PubDate     int64  `json:"pubdate"`
}

type bilibiliViewResponse struct {
	Code int `json:"code"`
	Data struct {
		BVID     string `json:"bvid"`
		Title    string `json:"title"`
		Desc     string `json:"desc"`
		Pic      string `json:"pic"`
		CID      int64  `json:"cid"`
		PubDate  int64  `json:"pubdate"`
		Duration int64  `json:"duration"`
		Owner    struct {
			Name string `json:"name"`
		} `json:"owner"`
	} `json:"data"`
}

type bilibiliPlayURLResponse struct {
	Code int `json:"code"`
	Data struct {
		Quality           int            `json:"quality"`
		AcceptQuality     []int          `json:"accept_quality"`
		AcceptDescription []string       `json:"accept_description"`
		VVoucher          string         `json:"v_voucher"`
		DURL              []bilibiliDURL `json:"durl"`
	} `json:"data"`
}

type bilibiliDURL struct {
	URL  string `json:"url"`
	Size int64  `json:"size"`
}

type bilibiliNavResponse struct {
	Code int `json:"code"`
	Data struct {
		WBIImg struct {
			ImgURL string `json:"img_url"`
			SubURL string `json:"sub_url"`
		} `json:"wbi_img"`
	} `json:"data"`
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

func (c *BilibiliClient) Search(
	ctx context.Context,
	query string,
	page int,
	pageSize int,
	codec *IDCodec,
) (SearchResponse, error) {
	include, excludeTerms := parseQuery(query)
	searchQuery := include
	if searchQuery == "" {
		searchQuery = query
	}
	signedURL, err := c.signedURL(
		ctx,
		"/x/web-interface/wbi/search/type",
		map[string]string{
			"search_type": "video",
			"keyword":     searchQuery,
			"page":        fmt.Sprintf("%d", page),
			"page_size":   fmt.Sprintf("%d", min(pageSize, 50)),
			"order":       "totalrank",
		},
	)
	if err != nil {
		return SearchResponse{}, err
	}
	var payload bilibiliSearchResponse
	if err := c.getJSON(ctx, signedURL, bilibiliWebHeaders(), &payload); err != nil {
		return SearchResponse{}, err
	}
	if payload.Code != 0 {
		return SearchResponse{}, &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: fmt.Sprintf("B站搜索接口返回错误 (code=%d)。", payload.Code),
		}
	}
	items := make([]VideoSummary, 0, len(payload.Data.Result))
	dotaQuery := isDotaQuery(searchQuery)
	for _, item := range payload.Data.Result {
		if item.BVID == "" || parseDuration(item.Duration) <= 0 {
			continue
		}
		title := cleanHTMLTitle(item.Title)
		if !dotaQuery && isCommentary(title, excludeTerms) {
			continue
		}
		items = append(items, toPublicSummary(item, codec))
	}
	total := payload.Data.NumResults
	if total <= 0 {
		total = len(items)
	}
	return SearchResponse{
		Query:    query,
		Page:     page,
		PageSize: pageSize,
		Total:    total,
		Items:    items,
	}, nil
}

func (c *BilibiliClient) GetDetails(
	ctx context.Context,
	bvid string,
	codec *IDCodec,
) (VideoDetails, error) {
	view, err := c.fetchView(ctx, bvid)
	if err != nil {
		return VideoDetails{}, err
	}
	playbackOptions, err := c.fetchPlaybackOptions(ctx, bvid, view.Data.CID, codec)
	if err != nil {
		return VideoDetails{}, err
	}
	return VideoDetails{
		ID:               codec.EncodeVideoID(view.Data.BVID),
		VideoKind:        "vod",
		Title:            strings.TrimSpace(defaultString(view.Data.Title, view.Data.BVID)),
		Description:      view.Data.Desc,
		Year:             yearString(view.Data.PubDate),
		Creators:         creatorList(view.Data.Owner.Name),
		ThumbnailURL:     normalizePicURL(view.Data.Pic),
		ThumbnailHeaders: directThumbnailHeaders(),
		DurationSeconds:  view.Data.Duration,
		PlaybackKind:     "vod",
		PlaybackOptions:  playbackOptions,
	}, nil
}

func (c *BilibiliClient) GetPlaybackOptions(
	ctx context.Context,
	bvid string,
	codec *IDCodec,
) ([]PlaybackOption, error) {
	view, err := c.fetchView(ctx, bvid)
	if err != nil {
		return nil, err
	}
	return c.fetchPlaybackOptions(ctx, bvid, view.Data.CID, codec)
}

func (c *BilibiliClient) fetchView(ctx context.Context, bvid string) (bilibiliViewResponse, error) {
	signedURL, err := c.signedURL(ctx, "/x/web-interface/wbi/view", map[string]string{
		"bvid": bvid,
	})
	if err != nil {
		return bilibiliViewResponse{}, err
	}
	var payload bilibiliViewResponse
	if err := c.getJSON(ctx, signedURL, bilibiliWebHeaders(), &payload); err != nil {
		return bilibiliViewResponse{}, err
	}
	if payload.Code != 0 || payload.Data.BVID == "" {
		return bilibiliViewResponse{}, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "未找到视频详情。",
		}
	}
	if payload.Data.CID == 0 {
		return bilibiliViewResponse{}, &apiError{
			Status:  http.StatusUnprocessableEntity,
			Code:    "NO_PLAYABLE_STREAM",
			Message: "无法获取视频流信息。",
		}
	}
	return payload, nil
}

func (c *BilibiliClient) fetchPlaybackOptions(
	ctx context.Context,
	bvid string,
	cid int64,
	codec *IDCodec,
) ([]PlaybackOption, error) {
	probe, err := c.fetchPlayURL(ctx, bvid, cid, 80)
	if err != nil {
		return nil, err
	}
	if voucherOnly(probe) {
		return nil, &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: "B站播放地址请求触发风控，请稍后重试。",
		}
	}
	if len(probe.Data.DURL) == 0 {
		return nil, &apiError{
			Status:  http.StatusUnprocessableEntity,
			Code:    "NO_PLAYABLE_STREAM",
			Message: "该普通视频暂无可由 App 直接播放的点播媒体地址。",
		}
	}

	descByQuality := make(map[int]string, len(probe.Data.AcceptQuality))
	for index, quality := range probe.Data.AcceptQuality {
		if index < len(probe.Data.AcceptDescription) && strings.TrimSpace(probe.Data.AcceptDescription[index]) != "" {
			descByQuality[quality] = probe.Data.AcceptDescription[index]
		}
	}

	candidateQualities := slices.Clone(probe.Data.AcceptQuality)
	if len(candidateQualities) == 0 {
		candidateQualities = []int{probe.Data.Quality}
	}
	if len(candidateQualities) > 4 {
		candidateQualities = candidateQualities[:4]
	}

	options := make([]PlaybackOption, 0, len(candidateQualities))
	seenLabels := make(map[string]struct{}, len(candidateQualities))
	for _, qn := range candidateQualities {
		data := probe
		if qn != probe.Data.Quality {
			data, err = c.fetchPlayURL(ctx, bvid, cid, qn)
			if err != nil {
				return nil, err
			}
		}
		if voucherOnly(data) {
			return nil, &apiError{
				Status:  http.StatusBadGateway,
				Code:    "UPSTREAM_UNAVAILABLE",
				Message: "B站播放地址请求触发风控，请稍后重试。",
			}
		}
		if len(data.Data.DURL) != 1 || strings.TrimSpace(data.Data.DURL[0].URL) == "" {
			continue
		}
		actualQuality := data.Data.Quality
		if actualQuality == 0 {
			actualQuality = qn
		}
		label := qualityLabel(actualQuality)
		if desc, ok := descByQuality[actualQuality]; ok {
			label = compactQualityDesc(desc, actualQuality)
		}
		if _, exists := seenLabels[label]; exists {
			continue
		}
		seenLabels[label] = struct{}{}
		options = append(options, PlaybackOption{
			ID:       codec.EncodePlaybackID(bvid, actualQuality),
			Kind:     "vod",
			Label:    label,
			URL:      data.Data.DURL[0].URL,
			MimeType: "video/mp4",
			Headers:  directPlaybackHeaders(),
		})
	}
	if len(options) == 0 {
		return nil, &apiError{
			Status:  http.StatusUnprocessableEntity,
			Code:    "NO_PLAYABLE_STREAM",
			Message: "该普通视频暂无可由 App 直接播放的点播媒体地址。",
		}
	}
	return options, nil
}

func (c *BilibiliClient) fetchPlayURL(
	ctx context.Context,
	bvid string,
	cid int64,
	qn int,
) (bilibiliPlayURLResponse, error) {
	signedURL, err := c.signedURL(ctx, "/x/player/wbi/playurl", map[string]string{
		"bvid":  bvid,
		"cid":   fmt.Sprintf("%d", cid),
		"fnval": "0",
		"qn":    fmt.Sprintf("%d", qn),
		"fourk": "1",
	})
	if err != nil {
		return bilibiliPlayURLResponse{}, err
	}
	var payload bilibiliPlayURLResponse
	if err := c.getJSON(ctx, signedURL, bilibiliWebHeaders(), &payload); err != nil {
		return bilibiliPlayURLResponse{}, err
	}
	if payload.Code != 0 {
		return bilibiliPlayURLResponse{}, &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: fmt.Sprintf("B站播放接口返回错误 (code=%d)。", payload.Code),
		}
	}
	return payload, nil
}

func (c *BilibiliClient) signedURL(
	ctx context.Context,
	path string,
	params map[string]string,
) (string, error) {
	imgKey, subKey, err := c.ensureWBIKeys(ctx)
	if err != nil {
		return "", err
	}
	signed := signWBIParams(params, imgKey, subKey)
	u, err := url.Parse(bilibiliAPIBase + path)
	if err != nil {
		return "", err
	}
	query := u.Query()
	for key, value := range signed {
		query.Set(key, value)
	}
	u.RawQuery = query.Encode()
	return u.String(), nil
}

func (c *BilibiliClient) ensureWBIKeys(ctx context.Context) (string, string, error) {
	// 快速路径：锁内检查缓存，命中即返回。
	c.keysMu.Lock()
	if c.imgKey != "" && c.subKey != "" && time.Now().Before(c.keyExpiry) {
		img, sub := c.imgKey, c.subKey
		c.keysMu.Unlock()
		return img, sub, nil
	}
	c.keysMu.Unlock()

	// 慢路径：锁外发起 HTTP 请求，避免阻塞其他 goroutine。
	var payload bilibiliNavResponse
	if err := c.getJSON(ctx, bilibiliAPIBase+"/x/web-interface/nav", bilibiliWebHeaders(), &payload); err != nil {
		return "", "", err
	}
	imgKey, subKey, err := wbiKeysOrNavError(payload)
	if err != nil {
		return "", "", err
	}

	// 锁内 double-check 更新：如果已被其他 goroutine 刷新，直接用已有的。
	c.keysMu.Lock()
	defer c.keysMu.Unlock()
	if c.imgKey != "" && c.subKey != "" && time.Now().Before(c.keyExpiry) {
		return c.imgKey, c.subKey, nil
	}
	c.imgKey = imgKey
	c.subKey = subKey
	c.keyExpiry = time.Now().Add(10 * time.Minute)
	return c.imgKey, c.subKey, nil
}

func wbiKeysOrNavError(payload bilibiliNavResponse) (string, string, error) {
	imgKey, subKey := wbiKeysFromNav(payload)
	if imgKey != "" && subKey != "" {
		return imgKey, subKey, nil
	}
	if payload.Code != 0 {
		return "", "", &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: fmt.Sprintf("B站导航接口返回错误 (code=%d)。", payload.Code),
		}
	}
	return "", "", &apiError{
		Status:  http.StatusBadGateway,
		Code:    "UPSTREAM_UNAVAILABLE",
		Message: "无法获取 B站签名密钥。",
	}
}

func wbiKeysFromNav(payload bilibiliNavResponse) (string, string) {
	return extractWBIKey(payload.Data.WBIImg.ImgURL), extractWBIKey(payload.Data.WBIImg.SubURL)
}

func (c *BilibiliClient) getJSON(
	ctx context.Context,
	rawURL string,
	headers map[string]string,
	target any,
) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: "无法连接 B站 API。",
		}
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &apiError{
			Status:  http.StatusBadGateway,
			Code:    "UPSTREAM_UNAVAILABLE",
			Message: "B站 API 返回了错误响应。",
		}
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

func bilibiliWebHeaders() map[string]string {
	return map[string]string{
		"User-Agent": bilibiliUserAgent,
		"Referer":    bilibiliWebOrigin,
	}
}

func directThumbnailHeaders() map[string]string {
	return map[string]string{
		"Referer": bilibiliWebOrigin,
	}
}

func directPlaybackHeaders() map[string]string {
	return map[string]string{
		"Referer": bilibiliWebOrigin,
		"Origin":  bilibiliWebOrigin,
	}
}

func parseQuery(query string) (string, []string) {
	tokens := strings.Fields(query)
	includes := make([]string, 0, len(tokens))
	excludes := make([]string, 0, len(tokens))
	negate := false
	for _, token := range tokens {
		switch token {
		case "NOT", "-":
			negate = true
		default:
			if negate {
				excludes = append(excludes, token)
				negate = false
			} else {
				includes = append(includes, token)
			}
		}
	}
	return strings.Join(includes, " "), excludes
}

func isCommentary(title string, extraExcludes []string) bool {
	allPatterns := make([]string, 0, len(commentaryKeywords)+len(extraExcludes))
	allPatterns = append(allPatterns, commentaryKeywords...)
	allPatterns = append(allPatterns, extraExcludes...)
	for _, pattern := range allPatterns {
		if pattern != "" && strings.Contains(title, pattern) {
			return true
		}
	}
	return false
}

func isDotaQuery(query string) bool {
	normalized := strings.ToLower(query)
	return strings.Contains(normalized, "dota") ||
		strings.Contains(normalized, "魔兽争霸") ||
		strings.Contains(normalized, "allstars")
}

func cleanHTMLTitle(raw string) string {
	return strings.TrimSpace(html.UnescapeString(htmlTagPattern.ReplaceAllString(raw, "")))
}

func normalizePicURL(raw string) string {
	if strings.HasPrefix(raw, "//") {
		return "https:" + raw
	}
	return raw
}

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

func compactQualityDesc(desc string, quality int) string {
	match := qualityDescPattern.FindString(desc)
	if match == "" {
		return qualityLabel(quality)
	}
	return strings.ToUpper(strings.ReplaceAll(match, " ", ""))
}

func voucherOnly(response bilibiliPlayURLResponse) bool {
	return strings.TrimSpace(response.Data.VVoucher) != "" && len(response.Data.DURL) == 0
}

func signWBIParams(params map[string]string, imgKey, subKey string) map[string]string {
	mixinKey := getMixinKey(imgKey + subKey)
	all := make(map[string]string, len(params)+2)
	for key, value := range params {
		all[key] = value
	}
	all["wts"] = fmt.Sprintf("%d", time.Now().Unix())
	keys := make([]string, 0, len(all))
	for key := range all {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		sanitizedValue := wbiSanitizeReplacer.Replace(all[key])
		parts = append(parts, url.QueryEscape(key)+"="+url.QueryEscape(sanitizedValue))
	}
	sum := md5.Sum([]byte(strings.Join(parts, "&") + mixinKey))
	all["w_rid"] = hex.EncodeToString(sum[:])
	return all
}

func getMixinKey(orig string) string {
	var builder strings.Builder
	for _, index := range mixinKeyEncTab {
		if index >= 0 && index < len(orig) {
			builder.WriteByte(orig[index])
		}
	}
	mixed := builder.String()
	if len(mixed) > 32 {
		return mixed[:32]
	}
	return mixed
}

func extractWBIKey(raw string) string {
	parts := strings.Split(raw, "/")
	file := parts[len(parts)-1]
	dot := strings.LastIndexByte(file, '.')
	if dot >= 0 {
		return file[:dot]
	}
	return file
}

func toPublicSummary(item bilibiliSearchItem, codec *IDCodec) VideoSummary {
	return VideoSummary{
		ID:               codec.EncodeVideoID(item.BVID),
		VideoKind:        "vod",
		Title:            defaultString(cleanHTMLTitle(item.Title), item.BVID),
		Description:      item.Description,
		Year:             yearString(item.PubDate),
		Creators:         creatorList(item.Author),
		ThumbnailURL:     normalizePicURL(item.Pic),
		ThumbnailHeaders: directThumbnailHeaders(),
		DurationSeconds:  parseDuration(item.Duration),
		PlaybackKind:     "vod",
	}
}

func creatorList(name string) []string {
	if strings.TrimSpace(name) == "" {
		return []string{}
	}
	return []string{name}
}

func yearString(unixSeconds int64) string {
	if unixSeconds <= 0 {
		return ""
	}
	return time.Unix(unixSeconds, 0).UTC().Format("2006")
}

func defaultString(value, fallback string) string {
	if strings.TrimSpace(value) != "" {
		return strings.TrimSpace(value)
	}
	return fallback
}
