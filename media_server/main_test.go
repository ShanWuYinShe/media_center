package main

import (
	"os"
	"path/filepath"
	"testing"
)

// TestCredentialsHotReload 锁定凭证热加载：文件变更后 currentCredentials
// 无需重启即返回新内容；文件损坏时保留旧值。
func TestCredentialsHotReload(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "creds.json")
	write := func(content string) {
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatalf("write creds: %v", err)
		}
	}
	write(`{"credentials":[{"provider":"bilibili","fields":{"SESSDATA":"old"}}]}`)
	s := &mediaServer{}
	creds, err := loadCredentials(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	s.credentials = creds
	if st, err := os.Stat(path); err == nil {
		s.credsStat = st
	}
	s.credentialsPath = path

	got := s.currentCredentials()
	if len(got) != 1 || got[0].Fields["SESSDATA"] != "old" {
		t.Fatalf("initial snapshot: %+v", got)
	}
	// 未变更：返回同一快照
	if got2 := s.currentCredentials(); len(got2) != 1 || got2[0].Fields["SESSDATA"] != "old" {
		t.Fatalf("unchanged snapshot: %+v", got2)
	}
	// 变更：mtime/size 不同 → 重载
	write(`{"credentials":[{"provider":"bilibili","fields":{"SESSDATA":"new","bili_jct":"x"}}]}`)
	got3 := s.currentCredentials()
	if len(got3) != 1 || got3[0].Fields["SESSDATA"] != "new" {
		t.Fatalf("after change: %+v", got3)
	}
	// 损坏：保留旧值
	write(`{not json`)
	if got4 := s.currentCredentials(); len(got4) != 1 || got4[0].Fields["SESSDATA"] != "new" {
		t.Fatalf("after corruption should keep old: %+v", got4)
	}
}
