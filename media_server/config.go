package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
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
	// 分类视频快照:预聚合 + 持久化 + 定期刷新 + 本地审校,详见 snapshot.go / curation.go。
	SnapshotEnabled    bool
	SnapshotDir        string
	SnapshotRefresh    time.Duration
	SnapshotTargetSize int
	CurationPath       string
	// 分类维护数据库(SQLite, system of record)。approved 行物化成运行时快照;
	// 路径为空或打开失败时优雅降级为实时聚合,详见 catalog.go。
	CatalogDBPath string
	// 内部管理接口令牌:非空时启用 /internal/* 管理接口(catalog 审校/seed/快照重建),
	// 请求需带匹配的 X-Internal-Token。为空则整组 /internal/* 接口关闭(404)。
	// 仅供内网可信调用方(workwork 的 admin)使用,绝不经公网暴露。详见 internal_api.go。
	InternalToken string
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

		SnapshotEnabled:    boolFromEnv(os.Getenv("MEDIA_SERVER_SNAPSHOT_ENABLED"), true),
		SnapshotDir:        getenv("MEDIA_SERVER_SNAPSHOT_DIR", "data/snapshots"),
		SnapshotRefresh:    durationFromHours(os.Getenv("MEDIA_SERVER_SNAPSHOT_REFRESH_HOURS"), 6*time.Hour),
		SnapshotTargetSize: positiveInt(os.Getenv("MEDIA_SERVER_SNAPSHOT_TARGET_SIZE"), 60),
		CatalogDBPath:      getenv("MEDIA_SERVER_CATALOG_DB", "data/catalog.db"),
		InternalToken:      strings.TrimSpace(os.Getenv("MEDIA_SERVER_INTERNAL_TOKEN")),
	}
	cfg.CurationPath = firstNonEmpty(
		os.Getenv("MEDIA_SERVER_CURATION_FILE"),
		os.Getenv("CURATION_FILE"),
		defaultCurationPath(cfg.CategoriesPath),
	)
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
		queries := trimStrings(item.SearchQueries)
		if len(queries) == 0 {
			if q := strings.TrimSpace(item.SearchQuery); q != "" {
				queries = []string{q}
			} else if len(item.Keywords) > 0 {
				if first := strings.TrimSpace(item.Keywords[0]); first != "" {
					queries = []string{first}
				}
			}
		}
		queries = dedupeStrings(queries)
		if len(queries) == 0 {
			return contentConfig{}, fmt.Errorf("category %s must define searchQuery/searchQueries or keywords", item.ID)
		}
		categories = append(categories, ContentCategory{
			ID:                 item.ID,
			Title:              item.Title,
			Description:        item.Description,
			Query:              queries[0],
			Queries:            queries,
			SourceQueries:      cloneStringMap(item.SourceQueries),
			Keywords:           trimStrings(item.Keywords),
			Excludes:           trimStrings(item.Excludes),
			MinDurationSeconds: item.MinDurationSeconds,
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

func durationFromHours(raw string, fallback time.Duration) time.Duration {
	if strings.TrimSpace(raw) == "" {
		return fallback
	}
	hours := positiveInt(raw, int(fallback/time.Hour))
	return time.Duration(hours) * time.Hour
}

// boolFromEnv 解析布尔型环境变量。空值返回默认;识别 1/true/yes/on 与 0/false/no/off。
func boolFromEnv(raw string, fallback bool) bool {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "":
		return fallback
	case "1", "true", "yes", "on", "enabled":
		return true
	case "0", "false", "no", "off", "disabled":
		return false
	default:
		return fallback
	}
}

// defaultCurationPath 默认把审校文件放在分类配置同目录下的 curation.json。
func defaultCurationPath(categoriesPath string) string {
	dir := filepath.Dir(strings.TrimSpace(categoriesPath))
	if dir == "" || dir == "." {
		return "src/curation.json"
	}
	return filepath.Join(dir, "curation.json")
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

// dedupeStrings 去掉重复项(保持首次出现顺序),用于规整分类的多搜索词列表。
func dedupeStrings(values []string) []string {
	if len(values) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(values))
	out := make([]string, 0, len(values))
	for _, value := range values {
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
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
