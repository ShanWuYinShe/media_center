package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

type AppConfig struct {
	Host           string
	Port           string
	CategoriesPath string
	PublicBaseURL  string
	// 分类维护数据库(SQLite, system of record)。分类浏览直接分页查询 approved 行;
	// 路径为空或打开失败时优雅降级为空分类列表,详见 catalog.go。
	CatalogDBPath string
	// Provider 凭证文件路径(由 admin 生成注入)。为空则不加载凭证。
	CredentialsPath string
	// 内部管理接口令牌:非空时启用 /internal/* 管理接口(catalog 审校/seed),
	// 请求需带匹配的 X-Internal-Token。为空则整组 /internal/* 接口关闭(404)。
	InternalToken         string
	AppUpdateManifestPath string
	AppUpdateAPKPath      string
	// 崩溃上报落盘目录(JSONL)。为空时默认 data/crash-reports。
	CrashReportDir string
	// App 端凭证访问令牌:凭证端点(/api/providers/credentials)要求请求携带
	// X-App-Token 并与之匹配(常量时间比较)。为空时凭证端点一律拒绝(安全默认),
	// 避免共享会话凭证(如 bilibili SESSDATA)被公网匿名抓取。
	AppAPIToken string
	// 允许作为 App 更新下载 Host 的公网域名(逗号分隔)。仅当 PublicBaseURL 未配置
	// 时用于校验 Host 头,防止 Host 头投毒把 apkUrl 指向攻击者域名。
	AllowedPublicHosts []string
	// vod_extractor(yt-dlp HTTP 封装)内网基址,如 http://vod_extractor:8790。
	// 为空时 youtube 的搜索/解析端点均返回 NOT_CONFIGURED。
	VODExtractorURL string
}

// contentCategories.json 的原始结构。sources 字段保留解析(向后兼容旧配置),
// 但新方案不再使用——server 端是纯目录服务,不调上游 API,delivery/provider
// 解析完全由 App 端按 provider 自行处理。
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
	SearchQueries []string          `json:"searchQueries"`
	SourceQueries map[string]string `json:"sourceQueries"`
	Keywords      []string          `json:"keywords"`
}

type contentConfig struct {
	Categories []ContentCategory
}

func loadAppConfig() (AppConfig, error) {
	cfg := AppConfig{
		Host:                  getenv("HOST", "0.0.0.0"),
		Port:                  getenv("PORT", "8787"),
		CategoriesPath:        firstNonEmpty(os.Getenv("CONTENT_CATEGORIES_FILE"), os.Getenv("MEDIA_SERVER_CONTENT_CATEGORIES_FILE"), "src/contentCategories.json"),
		PublicBaseURL:         normalizeBaseURL(firstNonEmpty(os.Getenv("MEDIA_SERVER_PUBLIC_URL"), os.Getenv("PUBLIC_BASE_URL"))),
		CatalogDBPath:         getenv("MEDIA_SERVER_CATALOG_DB", "data/catalog.db"),
		CredentialsPath:       firstNonEmpty(os.Getenv("PROVIDER_CREDENTIALS_FILE"), os.Getenv("MEDIA_SERVER_PROVIDER_CREDENTIALS_FILE")),
		InternalToken:         strings.TrimSpace(os.Getenv("MEDIA_SERVER_INTERNAL_TOKEN")),
		AppUpdateManifestPath: getenv("MEDIA_SERVER_APP_UPDATE_MANIFEST", "/config/app-update.json"),
		AppUpdateAPKPath:      getenv("MEDIA_SERVER_APP_UPDATE_APK", "/config/media-center-app.apk"),
		CrashReportDir:        getenv("MEDIA_SERVER_CRASH_DIR", "data/crash-reports"),
		AppAPIToken:           strings.TrimSpace(os.Getenv("MEDIA_SERVER_APP_API_TOKEN")),
		AllowedPublicHosts:    splitList(os.Getenv("MEDIA_SERVER_ALLOWED_HOSTS")),
		VODExtractorURL:       strings.TrimRight(strings.TrimSpace(os.Getenv("MEDIA_SERVER_VOD_EXTRACTOR_URL")), "/"),
	}
	return cfg, nil
}

// splitList 把逗号分隔的 env 值拆为去空格、去空的列表。
func splitList(raw string) []string {
	var out []string
	for _, part := range strings.Split(raw, ",") {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
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
			ID:            item.ID,
			Title:         item.Title,
			Description:   item.Description,
			Query:         queries[0],
			Queries:       queries,
			SourceQueries: cloneStringMap(item.SourceQueries),
			Keywords:      trimStrings(item.Keywords),
		})
	}
	return contentConfig{Categories: categories}, nil
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

// ProviderCredential 存储 provider 的认证凭证（如 bilibili 的 SESSDATA/bili_jct）。
// Fields 是任意 key-value 对，不同 provider 可有不同的字段集。
// 通过 /api/providers/credentials 返回给 App 端用于请求高分辨率内容。
type ProviderCredential struct {
	Provider string            `json:"provider"`
	Fields   map[string]string `json:"fields"`
}

type credentialsConfig struct {
	Credentials []ProviderCredential `json:"credentials"`
}

// loadCredentials 从 admin 生成的 providerCredentials.json 加载凭证。
// 路径为空或文件不存在时返回 nil（App 端降级为无凭证模式）。
func loadCredentials(path string) ([]ProviderCredential, error) {
	if path == "" {
		return nil, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("read credentials config: %w", err)
	}
	var raw credentialsConfig
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("decode credentials config: %w", err)
	}
	return raw.Credentials, nil
}
