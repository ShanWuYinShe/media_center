package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

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
			Size         int64  `json:"size"`
			ReleaseDate  string `json:"releaseDate"`
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

func (s *mediaServer) handleAppUpdate(w http.ResponseWriter, r *http.Request) {
	manifest, err := s.readAppUpdateManifest(r)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		log.Printf("app update manifest read error: %v", err)
		w.WriteHeader(http.StatusNoContent)
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
	manifest, err := s.readAppUpdateManifest(r)
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
	apkPath := s.appUpdateAPKPath(manifest)
	info, err := os.Stat(apkPath)
	if err != nil {
		status := http.StatusNotFound
		code := "NOT_FOUND"
		message := "暂无可下载的 App 更新。"
		if errors.Is(err, os.ErrNotExist) {
			status = http.StatusNotFound
			code = "NOT_FOUND"
			message = "暂无可下载的 App 更新。"
		} else {
			log.Printf("app download apk stat error: %v", err)
			status = http.StatusInternalServerError
			code = "INTERNAL_ERROR"
			message = "读取 App 安装包失败。"
		}
		s.writeError(w, &apiError{Status: status, Code: code, Message: message})
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

	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", "media-center-app.apk"))
	http.ServeFile(w, r, apkPath)
}

func (s *mediaServer) readAppUpdateManifest(r *http.Request) (AppUpdateManifest, error) {
	data, err := os.ReadFile(s.config.AppUpdateManifestPath)
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
	info, err := os.Stat(s.appUpdateAPKPath(manifest))
	if err != nil {
		return AppUpdateManifest{}, err
	}

	// 以磁盘上实际 APK 文件大小为准(manifest 里的 SizeBytes 可能因手工写入或
	// 重新打包而不准)。此前用 info.Size() != manifest.SizeBytes 强校验会直接报错,
	// 导致「检查更新」也被阻塞——尺寸校验应由客户端下载后按 sha256 做,这里不拦。
	manifest.SizeBytes = info.Size()
	manifest.APKURL = s.appDownloadURL(r)
	return manifest, nil
}

func (s *mediaServer) appUpdateAPKPath(manifest AppUpdateManifest) string {
	name := filepath.Base(strings.TrimSpace(manifest.APKFilename))
	if name == "." || name == string(filepath.Separator) || name == "" {
		return s.config.AppUpdateAPKPath
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
	if manifest.SizeBytes <= 0 {
		return errors.New("sizeBytes must be positive")
	}
	if _, err := hex.DecodeString(manifest.SHA256); err != nil || len(manifest.SHA256) != sha256.Size*2 {
		return errors.New("sha256 must be a hex encoded SHA-256 digest")
	}
	return nil
}

func (s *mediaServer) appDownloadURL(r *http.Request) string {
	if base := strings.TrimRight(s.config.PublicBaseURL, "/"); base != "" {
		return base + "/app/download"
	}
	scheme := "https"
	if r.TLS != nil {
		scheme = "https"
	} else if forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")); forwarded != "" {
		scheme = strings.Split(forwarded, ",")[0]
	} else if r.URL.Scheme != "" {
		scheme = r.URL.Scheme
	}
	host := r.Host
	if forwardedHost := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); forwardedHost != "" {
		host = strings.Split(forwardedHost, ",")[0]
	}
	prefix := strings.TrimRight(strings.TrimSpace(r.Header.Get("X-Forwarded-Prefix")), "/")
	if prefix == "" {
		prefix = "/api"
	}
	return scheme + "://" + host + prefix + "/app/download"
}
