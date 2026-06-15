package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
)

// ReviewOptions 控制 -catalog-review 子命令的行为。
type ReviewOptions struct {
	CategoryID string // 限定分类;空=全部分类
	DryRun     bool   // 只打印不写库
	Reviewer   string // 审校留痕;空则填默认
}

// runCatalogReview 是 -catalog-review 子命令入口:遍历(指定)分类,交互式逐条把 pending
// 候选审校成 approved/rejected。每条决定立即落库,中断不丢已审。
func (s *mediaServer) runCatalogReview(ctx context.Context, opts ReviewOptions) error {
	if s.catalog == nil {
		return fmt.Errorf("catalog review: catalog not opened")
	}
	reviewer := strings.TrimSpace(opts.Reviewer)
	if reviewer == "" {
		reviewer = "catalog-review-manual"
	}

	categories := s.categories
	if id := strings.TrimSpace(opts.CategoryID); id != "" {
		cat, ok := s.categoriesByID[id]
		if !ok {
			return fmt.Errorf("catalog review: unknown category %q", id)
		}
		categories = []ContentCategory{cat}
	}

	for _, category := range categories {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		items, err := s.catalog.PendingReviews(PendingReviewsOptions{CategoryID: category.ID})
		if err != nil {
			return fmt.Errorf("catalog review: load pending category=%s: %w", category.ID, err)
		}
		if len(items) == 0 {
			log.Printf("catalog review: category=%s no pending", category.ID)
			continue
		}
		fmt.Printf("\n=== 审校分类 %s: %d 条待审 ===\n", category.ID, len(items))
		approved, rejected, skipped := reviewInteractive(os.Stdin, os.Stdout, items, opts, reviewer, s.catalog)
		pending, _ := s.catalog.CountByStatus(category.ID, "pending")
		log.Printf("catalog review: category=%s approved=%d rejected=%d skipped=%d dry-run=%t pending=%d",
			category.ID, approved, rejected, skipped, opts.DryRun, pending)
	}
	return nil
}

// reviewInteractive 逐条展示候选并读取 stdin 命令,落库到 catalog。生产入口固定用 os.Stdin/Stdout。
func reviewInteractive(in io.Reader, out io.Writer, items []PendingReviewItem, opts ReviewOptions, reviewer string, catalog *Catalog) (approved, rejected, skipped int) {
	r := bufio.NewScanner(in)
	// 候选简介可能很长,放大 buffer 到 1MB,避免超长行触发 scanner 中止。
	r.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	return reviewInteractiveOn(r, out, items, opts, reviewer, catalog)
}

// reviewInteractiveOn 是 reviewInteractive 的可测试内核:用传入的 Scanner/Writer,
// 单测可注入 strings.NewReader 与 bytes.Buffer,不碰全局 io。返回各决定的计数。
func reviewInteractiveOn(r *bufio.Scanner, w io.Writer, items []PendingReviewItem, opts ReviewOptions, reviewer string, catalog *Catalog) (approved, rejected, skipped int) {
	for i, it := range items {
		writeReviewCard(w, i+1, len(items), it)
		fmt.Fprint(w, "[a]pprove / [r]eject / [s]kip / [q]uit > ")
		if !r.Scan() {
			break // EOF:剩余视为未处理
		}
		switch normalizeReviewCmd(r.Text()) {
		case "a":
			if applyReviewDecision(catalog, it, "approved", reviewer, opts.DryRun, w) {
				approved++
			} else {
				skipped++
			}
		case "r":
			if applyReviewDecision(catalog, it, "rejected", reviewer, opts.DryRun, w) {
				rejected++
			} else {
				skipped++
			}
		case "q":
			return
		default: // s / 空 / 未知 → 跳过
			skipped++
		}
	}
	return
}

// applyReviewDecision 落库一条审校决定;dry-run 时只打印不写。返回是否成功(失败已打印)。
func applyReviewDecision(catalog *Catalog, it PendingReviewItem, status, reviewer string, dryRun bool, w io.Writer) bool {
	if dryRun {
		fmt.Fprintf(w, "  [dry-run] %s <- %s (%s)\n", status, it.NativeID, strings.TrimSpace(it.Title))
		return true
	}
	if _, err := catalog.SetMemberStatus(it.CategoryID, it.Provider, it.NativeID, status, reviewer); err != nil {
		fmt.Fprintf(w, "  %s failed: %v\n", status, err)
		return false
	}
	return true
}

// normalizeReviewCmd 把用户输入归一成 a/r/s/q 四种动作之一(支持单字母与全词)。
func normalizeReviewCmd(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "a", "approve", "approved", "y", "yes":
		return "a"
	case "r", "reject", "rejected", "n", "no":
		return "r"
	case "q", "quit", "exit":
		return "q"
	default:
		return "s"
	}
}

// writeReviewCard 打印一条待审候选的可读卡片,含原始观看链接便于人工核对。
func writeReviewCard(w io.Writer, idx, total int, it PendingReviewItem) {
	fmt.Fprintf(w, "\n[%d/%d] (%s) %s\n", idx, total, it.CategoryID, strings.TrimSpace(it.Title))
	if len(it.Creators) > 0 {
		fmt.Fprintf(w, "  UP:    %s\n", strings.Join(it.Creators, ", "))
	}
	fmt.Fprintf(w, "  时长:  %s\n", formatDuration(it.DurationSec))
	if year := strings.TrimSpace(it.Year); year != "" {
		fmt.Fprintf(w, "  年份:  %s\n", year)
	}
	if desc := strings.TrimSpace(it.Description); desc != "" {
		fmt.Fprintf(w, "  简介:  %s\n", truncateRunes(desc, 240))
	}
	if it.SourceURL != "" {
		fmt.Fprintf(w, "  链接:  %s\n", it.SourceURL)
	}
}

// formatDuration 把秒数格式化为 H:MM:SS 或 M:SS;<=0 显示"未知"。
func formatDuration(sec int64) string {
	if sec <= 0 {
		return "未知"
	}
	h := sec / 3600
	m := (sec % 3600) / 60
	s := sec % 60
	if h > 0 {
		return fmt.Sprintf("%d:%02d:%02d", h, m, s)
	}
	return fmt.Sprintf("%d:%02d", m, s)
}

// truncateRunes 按 rune 截断字符串到 n,超出加省略号。
func truncateRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}
