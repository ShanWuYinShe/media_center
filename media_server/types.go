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
// 子串匹配(标题/简介/作者),跨分类去重。youtube 实时搜索端点
// (/api/providers/youtube/search)复用本结构;Total 为 int64 是因为该端点透传
// YouTube 的 pageInfo.totalResults,本地目录搜索的 total 在传入处做转换。
// VideoCollectionInfo 是合集元信息;Total 是“已审核剧集”的数量,不是来源
// 合集的全量集数(未过审的剧集不会出现在播放队列里)。
type VideoCollectionInfo struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Total int    `json:"total"`
}

// VideoCollectionResponse 是 /providers/{provider}/videos/{id}/collection 的响应:
// Items 只含已审核剧集,CurrentIndex 为当前视频在 Items 中的下标(-1 表示不在队列)。
type VideoCollectionResponse struct {
	Collection   *VideoCollectionInfo `json:"collection"`
	CurrentIndex int                  `json:"currentIndex"`
	Items        []VideoSummary       `json:"items"`
}

type SearchResponse struct {
	Query         string         `json:"query"`
	Total         int64          `json:"total"`
	Items         []VideoSummary `json:"items"`
	NextPageToken string         `json:"nextPageToken,omitempty"`
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
