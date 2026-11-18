package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var variantRe = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)

type AppUpdateManifest struct {
	VersionCode  int    `json:"versionCode"`
	VersionName  string `json:"versionName"`
	APKURL       string `json:"apkUrl"`
	APKFilename  string `json:"apkFilename,omitempty"`
	SizeBytes    int64  `json:"sizeBytes"`
	SHA256       string `json:"sha256"`
	ReleaseNotes string `json:"releaseNotes,omitempty"`
	PublishedAt  string `json:"publishedAt,omitempty"`
}

// UnmarshalJSON 兼容历史 manifest 文件:早期手工写入的文件用了 size/releaseDate
// 字段名(而非 sizeBytes/publishedAt),导致 SizeBytes 解析为 0、校验失败、更新
// 功能完全不可用。这里做向后兼容:优先用标准 tag,缺失时回退到旧字段名。
// (admin 的正常上传流程写的是正确的 sizeBytes/publishedAt,此兼容仅为兜底。)
func (m *AppUpdateManifest) UnmarshalJSON(data []byte) error {
	// alias 避免 UnmarshalJSON 无限递归
	type alias AppUpdateManifest
	if err := json.Unmarshal(data, (*alias)(m)); err != nil {
		return err
	}
	// 回退:标准字段未填时尝试历史字段名
	if m.SizeBytes == 0 {
		var legacy struct {
			Size        int64  `json:"size"`
			ReleaseDate string `json:"releaseDate"`
		}
		if json.Unmarshal(data, &legacy) == nil {
			if m.SizeBytes == 0 {
				m.SizeBytes = legacy.Size
			}
			if m.PublishedAt == "" {
				m.PublishedAt = legacy.ReleaseDate
			}
		}
	}
	return nil
}

func sanitizeVariant(v string) string {
	if v == "" || v == "normal" {
		return ""
	}
	if !variantRe.MatchString(v) {
		return ""
	}
	return v
}

func appUpdateVariant(r *http.Request) string {
	return sanitizeVariant(strings.TrimSpace(r.URL.Query().Get("variant")))
}

func (s *mediaServer) handleAppUpdate(w http.ResponseWriter, r *http.Request) {
	// 204(已是最新/清单缺失)也是 RFC 9110 默认可缓存状态码;不显式 no-store
	// 会让发布新版本后客户端长期拿到缓存 204,更新通道静默失效。
	w.Header().Set("Cache-Control", "no-store")
	variant := appUpdateVariant(r)
	manifest, err := s.readAppUpdateManifest(r, variant)
	if err != nil {
		// 只有"清单文件不存在"才等价于"没有发布过更新"(204);JSON 损坏、
		// sha256 非法、APK 缺失、下载地址无法推导等都是服务端故障,静默 204
		// 会让更新通道永久失效且没有任何信号(客户端以为已是最新)。
		if errors.Is(err, os.ErrNotExist) {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		log.Printf("app update manifest read error: %v", err)
		w.Header().Set("Cache-Control", "no-store")
		s.writeError(w, &apiError{
			Status:  http.StatusServiceUnavailable,
			Code:    "UPDATE_UNAVAILABLE",
			Message: "更新服务暂不可用，请稍后重试。",
		})
		return
	}

	currentCode := 0
	if raw := strings.TrimSpace(r.URL.Query().Get("versionCode")); raw != "" {
		if parsed, parseErr := strconv.Atoi(raw); parseErr == nil && parsed > 0 {
			currentCode = parsed
		}
	}
	if currentCode >= manifest.VersionCode {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	w.Header().Set("Cache-Control", "no-store")
	s.writeJSON(w, http.StatusOK, manifest)
}

func (s *mediaServer) handleAppDownload(w http.ResponseWriter, r *http.Request) {
	variant := appUpdateVariant(r)
	manifest, err := s.readAppUpdateManifest(r, variant)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			s.writeError(w, &apiError{
				Status:  http.StatusNotFound,
				Code:    "NOT_FOUND",
				Message: "暂无可下载的 App 更新。",
			})
			return
		}
		log.Printf("app download manifest read error: %v", err)
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "暂无可下载的 App 更新。",
		})
		return
	}
	apkPath := s.appUpdateAPKPath(manifest, variant)
	info, err := os.Stat(apkPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			s.writeError(w, &apiError{
				Status:  http.StatusNotFound,
				Code:    "NOT_FOUND",
				Message: "暂无可下载的 App 更新。",
			})
			return
		}
		log.Printf("app download apk stat error: %v", err)
		s.writeError(w, &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "读取 App 安装包失败。",
		})
		return
	}
	if info.Size() != manifest.SizeBytes {
		log.Printf("app download apk size mismatch: manifest=%d actual=%d", manifest.SizeBytes, info.Size())
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "暂无可下载的 App 更新。",
		})
		return
	}

	// 版本化下载地址(带 v=<sha256 前 12 位>)内容不可变,允许 CDN/浏览器长缓存。
	versionToken := appDownloadVersionToken(manifest.SHA256)
	requestedToken := strings.TrimSpace(r.URL.Query().Get("v"))
	if requestedToken != "" && !strings.EqualFold(requestedToken, versionToken) {
		// 旧令牌对应的内容已不存在(发版/重新打包)。直接 410 让客户端重新
		// 检查更新,而不是白下载一份 sha256 对不上的新 APK(旧实现会 200 返回
		// 当前 APK,客户端校验失败后整包重下)。
		w.Header().Set("Cache-Control", "no-store")
		s.writeError(w, &apiError{
			Status:  http.StatusGone,
			Code:    "APK_VERSION_GONE",
			Message: "安装包已更新，请重新检查更新。",
		})
		return
	}
	// 版本化地址内容不可变 → 长缓存;旧地址/直链下载内容会随发版变化 → no-store。
	if requestedToken != "" && strings.EqualFold(requestedToken, versionToken) {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		w.Header().Set("ETag", "\""+manifest.SHA256+"\"")
	} else {
		w.Header().Set("Cache-Control", "no-store")
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	// 下载文件名与变体对齐(normal: media-center-app.apk;其余变体加后缀),
	// 与 release-apk.sh 的服务器索引命名约定一致。
	filename := "media-center-app.apk"
	if variant != "" {
		filename = "media-center-app-" + variant + ".apk"
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
	http.ServeFile(w, r, apkPath)
}

func (s *mediaServer) appUpdateManifestPath(variant string) string {
	dir := filepath.Dir(s.config.AppUpdateManifestPath)
	base := filepath.Base(s.config.AppUpdateManifestPath)
	ext := filepath.Ext(base)
	name := strings.TrimSuffix(base, ext)
	if variant == "" {
		return filepath.Join(dir, name+ext)
	}
	return filepath.Join(dir, name+"-"+variant+ext)
}

func (s *mediaServer) appUpdateAPKManifestPath(variant string) string {
	dir := filepath.Dir(s.config.AppUpdateAPKPath)
	base := filepath.Base(s.config.AppUpdateAPKPath)
	ext := filepath.Ext(base)
	name := strings.TrimSuffix(base, ext)
	if variant == "" {
		return filepath.Join(dir, name+ext)
	}
	return filepath.Join(dir, name+"-"+variant+ext)
}

func (s *mediaServer) readAppUpdateManifest(r *http.Request, variant string) (AppUpdateManifest, error) {
	manifestPath := s.appUpdateManifestPath(variant)
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return AppUpdateManifest{}, err
	}
	var manifest AppUpdateManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return AppUpdateManifest{}, err
	}
	if err := validateAppUpdateManifest(manifest); err != nil {
		return AppUpdateManifest{}, err
	}
	info, err := os.Stat(s.appUpdateAPKPath(manifest, variant))
	if err != nil {
		return AppUpdateManifest{}, err
	}

	// 以磁盘上实际 APK 文件大小为准(manifest 里的 SizeBytes 可能因手工写入或
	// 重新打包而不准)。此前用 info.Size() != manifest.SizeBytes 强校验会直接报错,
	// 导致「检查更新」也被阻塞——尺寸校验应由客户端下载后按 sha256 做,这里不拦。
	manifest.SizeBytes = info.Size()
	manifest.APKURL = s.appDownloadURL(r, variant, manifest.SHA256)
	if manifest.APKURL == "" {
		// 推导失败(无 PUBLIC_URL 且 Host 不受信):不让客户端拿到空/被投毒的地址,
		// 直接按内部错误处理,运维可从日志看到被拒绝的 Host。
		return AppUpdateManifest{}, errors.New("app download URL cannot be derived from request host")
	}
	return manifest, nil
}

func (s *mediaServer) appUpdateAPKPath(manifest AppUpdateManifest, variant string) string {
	// filepath.Base("..") 仍返回 ".."，".." 穿透后 Join 得到上级目录，
	// http.ServeFile 会输出目录列表；APK 文件名只可能是合法文件名，
	// 这里校验非 ".." 且必须 .apk 结尾（manifest 位于 admin 只读卷，
	// 属纵深防御——写坏/被滥用的 manifest 不应扩大为任意文件读取）。
	name := filepath.Base(strings.TrimSpace(manifest.APKFilename))
	if name == "." || name == ".." || name == string(filepath.Separator) || name == "" ||
		!strings.EqualFold(filepath.Ext(name), ".apk") {
		return s.appUpdateAPKManifestPath(variant)
	}
	return filepath.Join(filepath.Dir(s.config.AppUpdateAPKPath), name)
}

func validateAppUpdateManifest(manifest AppUpdateManifest) error {
	if manifest.VersionCode <= 0 {
		return errors.New("versionCode must be positive")
	}
	if strings.TrimSpace(manifest.VersionName) == "" {
		return errors.New("versionName is required")
	}
	// 不校验 manifest.SizeBytes:响应里的体积以磁盘 APK 实际大小为准(见
	// readAppUpdateManifest)。早期手工清单可能没写 sizeBytes,强制要求会让
	// 整个更新检查不可用,而体积本身不是安全边界(sha256 才是)。
	if _, err := hex.DecodeString(manifest.SHA256); err != nil || len(manifest.SHA256) != sha256.Size*2 {
		return errors.New("sha256 must be a hex encoded SHA-256 digest")
	}
	return nil
}

func (s *mediaServer) appDownloadURL(r *http.Request, variant, sha256Hex string) string {
	versionToken := appDownloadVersionToken(sha256Hex)
	withVersion := func(downloadURL string) string {
		if versionToken == "" {
			return downloadURL
		}
		return downloadURL + "&v=" + versionToken
	}
	baseURL := s.config.PublicBaseURL
	if base := strings.TrimRight(baseURL, "/"); base != "" {
		return withVersion(base + "/app/download?variant=" + variant)
	}
	// 未配置 MEDIA_SERVER_PUBLIC_URL 时的兜底推导:Host 必须可信(显式白名单
	// 或本地开发网段),否则拒绝——防止 Host 头投毒把 apkUrl 指向攻击者域名,
	// App 盲从 manifest 即下载被替换的 APK。
	host := r.Host
	if forwardedHost := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); forwardedHost != "" {
		host = strings.Split(forwardedHost, ",")[0]
	}
	if !s.isAllowedDownloadHost(host) {
		log.Printf("app update: rejecting download host %q (set MEDIA_SERVER_PUBLIC_URL or MEDIA_SERVER_ALLOWED_HOSTS)", host)
		return ""
	}
	// 一律默认 https:公网入口是 Cloudflare/nginx TLS 终结,本服务自身不暴露 TLS;
	// 仅当反代显式告知 http(本地调试)或测试 URL 带 scheme 时才改写。
	scheme := "https"
	if forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")); forwarded != "" {
		scheme = strings.Split(forwarded, ",")[0]
	} else if r.URL.Scheme != "" {
		scheme = r.URL.Scheme
	}
	prefix := strings.TrimRight(strings.TrimSpace(r.Header.Get("X-Forwarded-Prefix")), "/")
	if prefix == "" {
		prefix = "/api"
	}
	return withVersion(scheme + "://" + host + prefix + "/app/download?variant=" + variant)
}

// appUpdateHealth 返回 update 通道状态(ok/missing/error)供 /healthz 使用。
// 清单损坏/sha 非法/APK 缺失会让更新检查返回 503,但只有暴露在探针/监控上,
// 运维才能在"所有电视都收不到更新"之前发现(旧实现静默 204 + 一行日志)。
func (s *mediaServer) appUpdateHealth() string {
	data, err := os.ReadFile(s.appUpdateManifestPath(""))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "missing"
		}
		return "error"
	}
	var manifest AppUpdateManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return "error"
	}
	if err := validateAppUpdateManifest(manifest); err != nil {
		return "error"
	}
	if _, err := os.Stat(s.appUpdateAPKPath(manifest, "")); err != nil {
		return "error"
	}
	return "ok"
}

// appDownloadVersionToken 返回 APK 下载地址的版本化令牌(sha256 前 12 位)。
// 令牌来自 manifest 的 sha256:同一版本重新打包(哪怕 versionCode 不变)也会变,
// 从而保证"URL 与内容一一对应",CDN 可以安全地用长缓存。
func appDownloadVersionToken(sha256Hex string) string {
	sha := strings.TrimSpace(strings.ToLower(sha256Hex))
	if len(sha) < 12 {
		return ""
	}
	return sha[:12]
}

// isAllowedDownloadHost 判断 Host 是否可信:显式白名单(MEDIA_SERVER_ALLOWED_HOSTS)、
// loopback 或私有网段(本地开发直连)放行;其余公网 Host 一律拒绝。
func (s *mediaServer) isAllowedDownloadHost(host string) bool {
	host = strings.TrimSpace(host)
	if host == "" {
		return false
	}
	// 去端口:支持 "example.com:443" 与 "[::1]:8787" 两种形态
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	} else if strings.HasPrefix(host, "[") {
		host = strings.Trim(host, "[]")
	}
	for _, allowed := range s.config.AllowedPublicHosts {
		if strings.EqualFold(allowed, host) {
			return true
		}
	}
	// 本地开发:localhost 主机名、loopback 或私有网段直连
	if strings.EqualFold(host, "localhost") {
		return true
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback() || ip.IsPrivate()
	}
	return false
}
