package main

// VideoSummary 是分类列表里的一条视频。server 端只做目录服务,
// 不再包含任何 delivery/playback/provider 相关字段——能否本地解析是
// provider 的固有属性,App 端按 provider 自行决定怎么拿播放地址。
type VideoSummary struct {
	ID              string `json:"id"`
	Title           string `json:"title"`
	Description     string `json:"description"`
	ThumbnailURL    string `json:"thumbnailUrl,omitempty"`
	DurationSeconds int64  `json:"durationSeconds,omitempty"`
}

type ProviderInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// ContentCategory 是 contentCategories.json 里的一条分类配置(仅服务端内部使用,
// 不直接暴露给 API;API 响应用的是 PublicCategory)。
type ContentCategory struct {
	ID            string
	Title         string
	Description   string
	Query         string
	Queries       []string
	SourceQueries map[string]string
	Keywords      []string
}

type PublicCategory struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Description string `json:"description"`
}

type ProvidersResponse struct {
	Items []ProviderInfo `json:"items"`
}

type CategoriesResponse struct {
	Items []PublicCategory `json:"items"`
}

type VideosResponse struct {
	Category *PublicCategory `json:"category,omitempty"`
	Page     int             `json:"page"`
	PageSize int             `json:"pageSize"`
	Total    int             `json:"total"`
	Items    []VideoSummary  `json:"items"`
}

// SearchResponse 是 /api/search 的响应:在已审核(approved)目录数据中按关键词
// 子串匹配(标题/简介/作者),跨分类去重。
type SearchResponse struct {
	Query string         `json:"query"`
	Total int            `json:"total"`
	Items []VideoSummary `json:"items"`
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

// providerDisplayName 返回某 provider 的展示名(给 App 在 UI 上显示)。
func providerDisplayName(providerID string) string {
	switch providerID {
	case "bilibili":
		return "B站"
	case "youtube":
		return "YouTube"
	case "internet-archive":
		return "Internet Archive"
	default:
		return providerID
	}
}
