package main

type rawConfig struct {
	Sources    []sourceConfig      `json:"sources"`
	Categories []rawCategoryConfig `json:"categories"`
}

type sourceConfig struct {
	ID      string `json:"id"`
	Enabled bool   `json:"enabled"`
}

type rawCategoryConfig struct {
	ID            string            `json:"id"`
	Title         string            `json:"title"`
	Description   string            `json:"description"`
	SearchQuery   string            `json:"searchQuery"`
	SourceQueries map[string]string `json:"sourceQueries"`
	Keywords      []string          `json:"keywords"`
	Excludes      []string          `json:"excludes"`
}

type ContentCategory struct {
	ID            string
	Title         string
	Description   string
	Query         string
	SourceQueries map[string]string
	Keywords      []string
	Excludes      []string
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
	ID               string            `json:"id"`
	VideoKind        string            `json:"videoKind"`
	Title            string            `json:"title"`
	Description      string            `json:"description"`
	Year             string            `json:"year,omitempty"`
	Creators         []string          `json:"creators"`
	ThumbnailURL     string            `json:"thumbnailUrl,omitempty"`
	ThumbnailHeaders map[string]string `json:"thumbnailHeaders,omitempty"`
	DurationSeconds  int64             `json:"durationSeconds,omitempty"`
	PlaybackKind     string            `json:"playbackKind"`
}

type VideoDetails struct {
	ID               string            `json:"id"`
	VideoKind        string            `json:"videoKind"`
	Title            string            `json:"title"`
	Description      string            `json:"description"`
	Year             string            `json:"year,omitempty"`
	Creators         []string          `json:"creators"`
	ThumbnailURL     string            `json:"thumbnailUrl,omitempty"`
	ThumbnailHeaders map[string]string `json:"thumbnailHeaders,omitempty"`
	DurationSeconds  int64             `json:"durationSeconds,omitempty"`
	PlaybackKind     string            `json:"playbackKind"`
	PlaybackOptions  []PlaybackOption  `json:"playbackOptions"`
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
