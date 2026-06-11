package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

type AppConfig struct {
	Host             string
	Port             string
	CategoriesPath   string
	SigningKey       string
	PublicBaseURL    string
	AssetURLTTL      time.Duration
	ProviderTimeout  time.Duration
	MaxCategoryPages int
	VODExtractorURL  string
	YouTubeAPIKey    string
	AssetCacheDir    string
	AssetCacheMax    int64
}

type contentConfig struct {
	Sources    []sourceConfig
	Categories []ContentCategory
}

func loadAppConfig() (AppConfig, error) {
	cfg := AppConfig{
		Host:             getenv("HOST", "0.0.0.0"),
		Port:             getenv("PORT", "8787"),
		CategoriesPath:   firstNonEmpty(os.Getenv("CONTENT_CATEGORIES_FILE"), os.Getenv("MEDIA_SERVER_CONTENT_CATEGORIES_FILE"), "src/contentCategories.json"),
		SigningKey:       firstNonEmpty(os.Getenv("MEDIA_SERVER_SIGNING_KEY"), os.Getenv("MEDIA_ASSET_SIGNING_KEY")),
		PublicBaseURL:    normalizeBaseURL(firstNonEmpty(os.Getenv("MEDIA_SERVER_PUBLIC_URL"), os.Getenv("PUBLIC_BASE_URL"))),
		AssetURLTTL:      durationFromSeconds(os.Getenv("MEDIA_SERVER_ASSET_URL_TTL_SECONDS"), 6*time.Hour),
		ProviderTimeout:  durationFromMillis(os.Getenv("PROVIDER_FETCH_TIMEOUT_MS"), 15*time.Second),
		MaxCategoryPages: positiveInt(os.Getenv("MEDIA_SERVER_CATEGORY_MAX_PAGES"), 6),
		VODExtractorURL:  strings.TrimSpace(os.Getenv("VOD_EXTRACTOR_URL")),
		YouTubeAPIKey:    strings.TrimSpace(os.Getenv("YOUTUBE_API_KEY")),
		AssetCacheDir:    getenv("MEDIA_ASSET_CACHE_DIR", "/cache"),
		AssetCacheMax:    positiveInt64(os.Getenv("MEDIA_ASSET_CACHE_MAX_BYTES"), 50*1024*1024*1024),
	}
	if cfg.SigningKey == "" {
		random := make([]byte, 32)
		if _, err := rand.Read(random); err != nil {
			return AppConfig{}, fmt.Errorf("generate signing key: %w", err)
		}
		cfg.SigningKey = base64.RawURLEncoding.EncodeToString(random)
	}
	return cfg, nil
}

func loadCategories(path string) ([]ContentCategory, error) {
	cfg, err := loadContentConfig(path)
	if err != nil {
		return nil, err
	}
	return cfg.Categories, nil
}

func loadContentConfig(path string) (contentConfig, error) {
	rawBytes, err := os.ReadFile(path)
	if err != nil {
		return contentConfig{}, fmt.Errorf("read categories config: %w", err)
	}
	var raw rawConfig
	if err := json.Unmarshal(rawBytes, &raw); err != nil {
		return contentConfig{}, fmt.Errorf("decode categories config: %w", err)
	}
	if len(raw.Categories) == 0 {
		return contentConfig{}, fmt.Errorf("contentCategories.json must include at least one category")
	}
	seen := make(map[string]struct{}, len(raw.Categories))
	categories := make([]ContentCategory, 0, len(raw.Categories))
	for _, item := range raw.Categories {
		if item.ID == "" || item.Title == "" || item.Description == "" {
			return contentConfig{}, fmt.Errorf("category id/title/description must be non-empty")
		}
		if _, ok := seen[item.ID]; ok {
			return contentConfig{}, fmt.Errorf("duplicate category id: %s", item.ID)
		}
		seen[item.ID] = struct{}{}
		query := strings.TrimSpace(item.SearchQuery)
		if query == "" && len(item.Keywords) > 0 {
			query = strings.TrimSpace(item.Keywords[0])
		}
		if query == "" {
			return contentConfig{}, fmt.Errorf("category %s must define searchQuery or keywords", item.ID)
		}
		categories = append(categories, ContentCategory{
			ID:            item.ID,
			Title:         item.Title,
			Description:   item.Description,
			Query:         query,
			SourceQueries: cloneStringMap(item.SourceQueries),
			Keywords:      trimStrings(item.Keywords),
			Excludes:      trimStrings(item.Excludes),
		})
	}
	return contentConfig{Sources: normalizeSourceConfig(raw.Sources), Categories: categories}, nil
}

func normalizeSourceConfig(sources []sourceConfig) []sourceConfig {
	if len(sources) == 0 {
		return []sourceConfig{{ID: "bilibili", Enabled: true}}
	}
	out := make([]sourceConfig, 0, len(sources))
	for _, source := range sources {
		id := strings.TrimSpace(source.ID)
		if id == "" {
			continue
		}
		out = append(out, sourceConfig{ID: id, Enabled: source.Enabled, Direct: source.Direct})
	}
	return out
}

// defaultSourceDirect 是各内置源“国内是否可直连”的默认判断：
// 国内源(bilibili)默认直连；国外源默认走服务端代理。
var defaultSourceDirect = map[string]bool{
	"bilibili":         true,
	"youtube":          false,
	"internet-archive": false,
}

// sourceDeliveryMode 解析某个源的 delivery 模式。优先用配置里的 direct 显式值，
// 其次用内置默认；未知源默认走代理(proxy)，对国内访问更安全。
func sourceDeliveryMode(sources []sourceConfig, id string) string {
	for _, source := range sources {
		if source.ID != id {
			continue
		}
		if source.Direct != nil {
			return deliveryModeFromDirect(*source.Direct)
		}
		break
	}
	if direct, ok := defaultSourceDirect[id]; ok {
		return deliveryModeFromDirect(direct)
	}
	return DeliveryProxy
}

func deliveryModeFromDirect(direct bool) string {
	if direct {
		return DeliveryDirect
	}
	return DeliveryProxy
}

func sourceDeliveryModes(sources []sourceConfig, providerIDs []string) map[string]string {
	modes := make(map[string]string, len(providerIDs))
	for _, id := range providerIDs {
		modes[id] = sourceDeliveryMode(sources, id)
	}
	return modes
}

func positiveInt(raw string, fallback int) int {
	if raw == "" {
		return fallback
	}
	var value int
	if _, err := fmt.Sscanf(strings.TrimSpace(raw), "%d", &value); err != nil || value <= 0 {
		return fallback
	}
	return value
}

func positiveInt64(raw string, fallback int64) int64 {
	if raw == "" {
		return fallback
	}
	var value int64
	if _, err := fmt.Sscanf(strings.TrimSpace(raw), "%d", &value); err != nil || value <= 0 {
		return fallback
	}
	return value
}

func durationFromMillis(raw string, fallback time.Duration) time.Duration {
	if raw == "" {
		return fallback
	}
	ms := positiveInt(raw, int(fallback/time.Millisecond))
	return time.Duration(ms) * time.Millisecond
}

func durationFromSeconds(raw string, fallback time.Duration) time.Duration {
	if raw == "" {
		return fallback
	}
	seconds := positiveInt(raw, int(fallback/time.Second))
	return time.Duration(seconds) * time.Second
}

// normalizeBaseURL 去掉末尾斜杠，便于后续拼接 /asset。空值原样返回。
func normalizeBaseURL(value string) string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return ""
	}
	return strings.TrimRight(trimmed, "/")
}

func getenv(key, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

func trimStrings(values []string) []string {
	if len(values) == 0 {
		return nil
	}
	out := make([]string, 0, len(values))
	for _, value := range values {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func cloneStringMap(value map[string]string) map[string]string {
	if len(value) == 0 {
		return nil
	}
	out := make(map[string]string, len(value))
	for key, item := range value {
		if strings.TrimSpace(key) == "" || strings.TrimSpace(item) == "" {
			continue
		}
		out[key] = item
	}
	if len(out) == 0 {
		return nil
	}
	return out
}
