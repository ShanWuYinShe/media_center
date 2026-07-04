package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// TestAppUpdateManifestUnmarshal 验证 manifest JSON 解析的字段兼容性。
// admin 正常上传写 sizeBytes/publishedAt;早期手工文件写 size/releaseDate。
// 两种都必须正确解析出 SizeBytes,否则校验失败、更新功能完全不可用。
func TestAppUpdateManifestUnmarshal(t *testing.T) {
	sha := strings.Repeat("a", 64)
	tests := []struct {
		name     string
		json     string
		wantErr  bool
		wantSize int64
		wantPub  string
	}{
		{
			name:     "standard fields (admin-generated)",
			json:     `{"versionCode":20260625,"versionName":"1.0","sizeBytes":7078970,"sha256":"` + sha + `"}`,
			wantSize: 7078970,
		},
		{
			name:     "legacy size field (hand-written)",
			json:     `{"versionCode":20260625,"versionName":"1.0","size":7078970,"sha256":"` + sha + `","releaseDate":"2026-06-21T14:34:41Z"}`,
			wantSize: 7078970,
			wantPub:  "2026-06-21T14:34:41Z",
		},
		{
			name:     "standard overrides legacy when both present",
			json:     `{"versionCode":20260625,"versionName":"1.0","size":111,"sizeBytes":222,"sha256":"` + sha + `","publishedAt":"new","releaseDate":"old"}`,
			wantSize: 222,
			wantPub:  "new",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var m AppUpdateManifest
			if err := json.Unmarshal([]byte(tt.json), &m); err != nil {
				if !tt.wantErr {
					t.Fatalf("unexpected error: %v", err)
				}
				return
			}
			if m.SizeBytes != tt.wantSize {
				t.Errorf("SizeBytes = %d, want %d", m.SizeBytes, tt.wantSize)
			}
			if tt.wantPub != "" && m.PublishedAt != tt.wantPub {
				t.Errorf("PublishedAt = %q, want %q", m.PublishedAt, tt.wantPub)
			}
		})
	}
}
