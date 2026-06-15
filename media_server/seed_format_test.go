package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSeedCategoryFilesUseImportEnvelope(t *testing.T) {
	paths, err := filepath.Glob("../seeds/categories/*.json")
	if err != nil {
		t.Fatalf("glob seed files: %v", err)
	}
	if len(paths) == 0 {
		t.Fatal("no seed category files found")
	}

	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			raw, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("read seed file: %v", err)
			}
			var doc struct {
				Items []internalImportItem `json:"items"`
			}
			if err := json.Unmarshal(raw, &doc); err != nil {
				t.Fatalf("seed file must be a JSON object import envelope: %v", err)
			}
			if len(doc.Items) == 0 {
				t.Fatal("seed file items must be non-empty")
			}
			seen := make(map[string]struct{}, len(doc.Items))
			for i, item := range doc.Items {
				if item.CategoryID == "" || item.Provider == "" || item.NativeID == "" || item.Title == "" {
					t.Fatalf("items[%d] missing required categoryId/provider/nativeId/title", i)
				}
				key := item.Provider + ":" + item.NativeID
				if _, dup := seen[key]; dup {
					t.Fatalf("duplicate native video id: %s", key)
				}
				seen[key] = struct{}{}
			}
		})
	}
}
