package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
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

func (s *mediaServer) handleAppUpdate(w http.ResponseWriter, r *http.Request) {
	manifest, err := s.readAppUpdateManifest(r)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		s.writeError(w, &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "读取 App 更新信息失败。",
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
		s.writeError(w, &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "读取 App 更新信息失败。",
		})
		return
	}
	apkPath := s.appUpdateAPKPath(manifest)
	info, err := os.Stat(apkPath)
	if err != nil {
		status := http.StatusInternalServerError
		code := "INTERNAL_ERROR"
		message := "读取 App 安装包失败。"
		if errors.Is(err, os.ErrNotExist) {
			status = http.StatusNotFound
			code = "NOT_FOUND"
			message = "暂无可下载的 App 更新。"
		}
		s.writeError(w, &apiError{Status: status, Code: code, Message: message})
		return
	}
	if info.Size() != manifest.SizeBytes {
		s.writeError(w, &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "App 安装包元数据不一致。",
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

	if info.Size() != manifest.SizeBytes {
		return AppUpdateManifest{}, errors.New("app update apk size mismatch")
	}
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
