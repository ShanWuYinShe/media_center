package main

// loadContentConfig 的校验与查询回退分支测试（config.go）：
// 重复 id、空字段、searchQueries > searchQuery > keywords[0] 三级回退。

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func writeCategoriesFile(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "contentCategories.json")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write temp config: %v", err)
	}
	return path
}

func mustLoadContentConfig(t *testing.T, content string) contentConfig {
	t.Helper()
	cfg, err := loadContentConfig(writeCategoriesFile(t, content))
	if err != nil {
		t.Fatalf("loadContentConfig: %v", err)
	}
	return cfg
}

func TestLoadContentConfigSearchQueriesFallback(t *testing.T) {
	// 一级:searchQueries 优先(多条保留、Query 取首条);空白项被 trim、去重
	cfg := mustLoadContentConfig(t, `{"categories":[
		{"id":"a","title":"A","description":"da",
		 "searchQueries":["  q1 ","q1",""," q2  "],
		 "searchQuery":"ignored","keywords":["ignored"]}
	]}`)
	got := cfg.Categories[0]
	if got.Query != "q1" {
		t.Errorf("Query = %q, want q1", got.Query)
	}
	if want := []string{"q1", "q2"}; !reflect.DeepEqual(got.Queries, want) {
		t.Errorf("Queries = %v, want %v", got.Queries, want)
	}

	// 二级:searchQueries 缺失时回退 searchQuery
	cfg = mustLoadContentConfig(t, `{"categories":[
		{"id":"b","title":"B","description":"db","searchQuery":" single ","keywords":["ignored"]}
	]}`)
	got = cfg.Categories[0]
	if got.Query != "single" || !reflect.DeepEqual(got.Queries, []string{"single"}) {
		t.Errorf("Query/Queries = %q/%v, want single/[single]", got.Query, got.Queries)
	}

	// 三级:searchQueries/searchQuery 均缺失时回退 keywords[0]
	cfg = mustLoadContentConfig(t, `{"categories":[
		{"id":"c","title":"C","description":"dc","keywords":[" kw1 ","","kw2"]}
	]}`)
	got = cfg.Categories[0]
	if got.Query != "kw1" || !reflect.DeepEqual(got.Queries, []string{"kw1"}) {
		t.Errorf("Query/Queries = %q/%v, want kw1/[kw1]", got.Query, got.Queries)
	}
	// keywords 本身按 trim 后保留全部
	if want := []string{"kw1", "kw2"}; !reflect.DeepEqual(got.Keywords, want) {
		t.Errorf("Keywords = %v, want %v", got.Keywords, want)
	}
}

func TestLoadContentConfigMissingQueries(t *testing.T) {
	// 三级全空:searchQueries 空数组、searchQuery 空白、keywords 全空白 → 报错
	_, err := loadContentConfig(writeCategoriesFile(t, `{"categories":[
		{"id":"a","title":"A","description":"da","searchQueries":["  "],"searchQuery":"  ","keywords":["","  "]}
	]}`))
	if err == nil || !strings.Contains(err.Error(), "must define searchQuery/searchQueries or keywords") {
		t.Fatalf("err = %v, want must define searchQuery/searchQueries or keywords", err)
	}

	// keywords 非空但首项为空白(其余非空)→ 回退失败同样报错
	_, err = loadContentConfig(writeCategoriesFile(t, `{"categories":[
		{"id":"a","title":"A","description":"da","keywords":["  ","kw2"]}
	]}`))
	if err == nil || !strings.Contains(err.Error(), "must define searchQuery/searchQueries or keywords") {
		t.Fatalf("err = %v, want must define searchQuery/searchQueries or keywords", err)
	}
}

func TestLoadContentConfigDuplicateID(t *testing.T) {
	_, err := loadContentConfig(writeCategoriesFile(t, `{"categories":[
		{"id":"dup","title":"A","description":"da","searchQuery":"q1"},
		{"id":"dup","title":"B","description":"db","searchQuery":"q2"}
	]}`))
	if err == nil || !strings.Contains(err.Error(), "duplicate category id: dup") {
		t.Fatalf("err = %v, want duplicate category id: dup", err)
	}
}

func TestLoadContentConfigEmptyFields(t *testing.T) {
	// id / title / description 任一为空串都拒绝
	// （校验只判 == ""，不做 trim：空白字符串字段会被接受，与实现一致）
	for _, tc := range []struct{ name, id, title, desc string }{
		{"空 id", "", "A", "da"},
		{"空 title", "a", "", "da"},
		{"空 description", "a", "A", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			raw := `{"categories":[{"id":"` + tc.id + `","title":"` + tc.title + `","description":"` + tc.desc + `","searchQuery":"q"}]}`
			_, err := loadContentConfig(writeCategoriesFile(t, raw))
			if err == nil || !strings.Contains(err.Error(), "id/title/description must be non-empty") {
				t.Fatalf("err = %v, want id/title/description must be non-empty", err)
			}
		})
	}
}

func TestLoadContentConfigInvalidInput(t *testing.T) {
	// 无 categories 字段/空数组
	_, err := loadContentConfig(writeCategoriesFile(t, `{"categories":[]}`))
	if err == nil || !strings.Contains(err.Error(), "must include at least one category") {
		t.Fatalf("err = %v, want must include at least one category", err)
	}

	// 非法 JSON
	_, err = loadContentConfig(writeCategoriesFile(t, `{"categories":[`))
	if err == nil || !strings.Contains(err.Error(), "decode categories config") {
		t.Fatalf("err = %v, want decode categories config", err)
	}

	// 文件不存在
	_, err = loadContentConfig(filepath.Join(t.TempDir(), "missing.json"))
	if err == nil || !strings.Contains(err.Error(), "read categories config") {
		t.Fatalf("err = %v, want read categories config", err)
	}
}

func TestLoadContentConfigSourceQueries(t *testing.T) {
	// sourceQueries 的空 key / 空 value 被过滤;全空时为 nil
	cfg := mustLoadContentConfig(t, `{"categories":[
		{"id":"a","title":"A","description":"da","searchQuery":"q",
		 "sourceQueries":{"bilibili":"bv","":"dropped","bad":""}}
	]}`)
	got := cfg.Categories[0].SourceQueries
	if want := map[string]string{"bilibili": "bv"}; !reflect.DeepEqual(got, want) {
		t.Errorf("SourceQueries = %v, want %v", got, want)
	}

	cfg = mustLoadContentConfig(t, `{"categories":[
		{"id":"a","title":"A","description":"da","searchQuery":"q","sourceQueries":{"bad":""}}
	]}`)
	if cfg.Categories[0].SourceQueries != nil {
		t.Errorf("SourceQueries = %v, want nil", cfg.Categories[0].SourceQueries)
	}
}
