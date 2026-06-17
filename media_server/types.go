package main

// Delivery 模式：决定 App 如何获取缩略图 / 播放流。
//
// 注意：该区分只看 **App（国内）能否直连上游**，与服务端无关——服务端可访问所有网络，
// 任何源都能直接回源，永远不需要出站代理。
//   - 缩略图：体积小，统一用 DeliveryProxy，避免 Android 客户端受上游老旧证书链、
//     Referer/UA 差异或区域网络策略影响。
//   - 播放流 DeliveryDirect：App 国内可直连的源（如 Bilibili），服务端只下发上游 URL +
//     companion headers，由 App 直连上游，避免大量媒体流量集中到服务端 IP 触发上游限流。
//   - 播放流 DeliveryProxy：App 国内无法直连的源（国外源），由服务端直连上游并通过
//     /api/asset 回源转发；下发的是签名代理地址，App 不需要也拿不到上游请求头。
const (
	DeliveryDirect = "direct"
	DeliveryProxy  = "proxy"
)

// isClientResolvedProvider 判断某来源是否由 App 端自己解析播放地址:
// 国内可直连的源由 App 拿到 VideoSummary.provider + nativeId 后自行调上游 API,
// server 端不再代理解析(避免 server 端 IP 被限流 + 省回源带宽)。
// 当前仅 bilibili 走此路径;youtube 等国外源仍由 server + vod_extractor 代理。
// App 端识别 VideoSummary.provider 后自行分流,PlaybackOption 不需要任何特殊标记。
func isClientResolvedProvider(providerID string) bool {
	return providerID == "bilibili"
}

type rawConfig struct {
	Sources    []sourceConfig      `json:"sources"`
	Categories []rawCategoryConfig `json:"categories"`
}

type sourceConfig struct {
	ID      string `json:"id"`
	Enabled bool   `json:"enabled"`
	// Direct 表示该源对 App 是否国内可直连。
	//   - true：App 直连上游（direct）。
	//   - false：服务端代理回源（proxy）。
	//   - 省略：按 defaultSourceDirect 的内置判断。
	Direct *bool `json:"direct,omitempty"`
}

type rawCategoryConfig struct {
	ID            string            `json:"id"`
	Title         string            `json:"title"`
	Description   string            `json:"description"`
	SearchQuery   string            `json:"searchQuery"`
	SearchQueries []string          `json:"searchQueries"`
	SourceQueries map[string]string `json:"sourceQueries"`
	Keywords      []string          `json:"keywords"`
}

type ContentCategory struct {
	ID          string
	Title       string
	Description string
	Query       string
	// Queries 是该分类聚合时使用的多个搜索词;为空时回退到 [Query]。
	// 多搜索词能显著扩大候选池,缓解“数据太少”。
	Queries       []string
	SourceQueries map[string]string
	Keywords      []string
}

type PublicCategory struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Description string `json:"description"`
}

type CategoryResponse struct {
	Items []PublicCategory `json:"items"`
}

type SearchResponse struct {
	Query    string          `json:"query"`
	Category *PublicCategory `json:"category,omitempty"`
	Page     int             `json:"page"`
	PageSize int             `json:"pageSize"`
	Total    int             `json:"total"`
	Items    []VideoSummary  `json:"items"`
}

type VideoSummary struct {
	ID                string            `json:"id"`
	VideoKind         string            `json:"videoKind"`
	Title             string            `json:"title"`
	Description       string            `json:"description"`
	Year              string            `json:"year,omitempty"`
	Creators          []string          `json:"creators"`
	ThumbnailURL      string            `json:"thumbnailUrl,omitempty"`
	ThumbnailHeaders  map[string]string `json:"thumbnailHeaders,omitempty"`
	ThumbnailDelivery string            `json:"thumbnailDelivery,omitempty"`
	DurationSeconds   int64             `json:"durationSeconds,omitempty"`
	PlaybackKind      string            `json:"playbackKind"`
	// Provider + NativeID 透传 catalog 原始来源(如 bilibili/BV...),
	// App 端据此判断是否需要自己解析播放地址。国内可直连源(direct)的 PlaybackOption
	// 走 client-resolve 路径时必填;其他情况省略。
	Provider string `json:"provider,omitempty"`
	NativeID string `json:"nativeId,omitempty"`
}

type VideoDetails struct {
	ID                string            `json:"id"`
	VideoKind         string            `json:"videoKind"`
	Title             string            `json:"title"`
	Description       string            `json:"description"`
	Year              string            `json:"year,omitempty"`
	Creators          []string          `json:"creators"`
	ThumbnailURL      string            `json:"thumbnailUrl,omitempty"`
	ThumbnailHeaders  map[string]string `json:"thumbnailHeaders,omitempty"`
	ThumbnailDelivery string            `json:"thumbnailDelivery,omitempty"`
	DurationSeconds   int64             `json:"durationSeconds,omitempty"`
	PlaybackKind      string            `json:"playbackKind"`
	PlaybackOptions   []PlaybackOption  `json:"playbackOptions"`
	// Provider + NativeID 同 VideoSummary。
	Provider string `json:"provider,omitempty"`
	NativeID string `json:"nativeId,omitempty"`
}

type PlaybackOptionsResponse struct {
	Items []PlaybackOption `json:"items"`
}

type PlaybackOption struct {
	ID       string            `json:"id"`
	Kind     string            `json:"kind"`
	Label    string            `json:"label"`
	URL      string            `json:"url"`
	MimeType string            `json:"mimeType,omitempty"`
	Headers  map[string]string `json:"headers,omitempty"`
	Delivery string            `json:"delivery,omitempty"`
}

type apiErrorBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type apiErrorEnvelope struct {
	Error apiErrorBody `json:"error"`
}

type apiError struct {
	Status  int
	Code    string
	Message string
}

func (e *apiError) Error() string {
	return e.Message
}
