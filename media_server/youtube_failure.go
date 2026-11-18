package main

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---- YouTube 上游失败分类 ----
//
// vod_extractor 失败响应带 reason 字段(见 vod_extractor/README.md)。审计发现
// 旧实现把「cookies 过期 / 风控 / extractor 重启 / 配置错误 / 超时」这类
// 全站性故障也映射成 502 RESOLVE_FAILED「该视频可能受限」:用户会换视频反复
// 触发完整 yt-dlp 解析(秒级~数十秒),占满 extractor 仅有的 2 个并发槽,
// 连带搜索/分类也 503;同时这些失败还会按 videoID 负缓存 5 分钟。
//
// 这里把失败分成两类:
//   - 内容类(content):视频本身不可播(unavailable/geo/private/no_formats)→
//     维持 502 RESOLVE_FAILED,允许按视频负缓存;
//   - 基础设施类(infra):除内容类以外的失败 → 503 + Retry-After,不按视频
//     负缓存,并计入进程级熔断。
type youtubeFailureClass int

const (
	youtubeFailureContent youtubeFailureClass = iota
	youtubeFailureInfra
)

// contentFailureReasons 是「该视频本身不可播」的上游 reason;其余 reason
// (bot_check/rate_limited/timeout/cookies/config/...)一律按基础设施故障处理。
var contentFailureReasons = map[string]struct{}{
	"unavailable":       {},
	"video_unavailable": {},
	"not_available":     {},
	"geo_blocked":       {},
	"private":           {},
	"no_formats":        {},
	"not_found":         {},
}

// classifyYouTubeFailure 给 handler 用:决定错误语义(502 视频文案 vs 503 故障文案)。
// 旧版 extractor 的 502 可能不带 reason,保持旧语义(内容类、可负缓存)以兼容。
func classifyYouTubeFailure(err error) youtubeFailureClass {
	if err == nil {
		return youtubeFailureContent
	}
	var ue *upstreamError
	if errors.As(err, &ue) {
		return classifyYouTubeFailureReason(ue.reason)
	}
	return youtubeFailureInfra
}

// classifyYouTubeFailureReason 同时用于缓存中的失败 reason(负缓存旧条目)映射。
func classifyYouTubeFailureReason(reason string) youtubeFailureClass {
	reason = strings.ToLower(strings.TrimSpace(reason))
	if _, ok := contentFailureReasons[reason]; ok {
		return youtubeFailureContent
	}
	if reason == "" {
		// 无 reason 的失败(旧 extractor 的 502)按内容类处理,保证兼容。
		return youtubeFailureContent
	}
	return youtubeFailureInfra
}

// youtubeFailureLabel 返回可在日志/healthz 里展示的失败标签。
func youtubeFailureLabel(err error) string {
	var ue *upstreamError
	if errors.As(err, &ue) && ue.reason != "" {
		return ue.reason
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	if errors.Is(err, context.Canceled) {
		return "canceled"
	}
	return "transport"
}

// ---- 基础设施失败的进程级熔断 ----
//
// cookies 过期/风控/extractor 挂掉时,每个新视频仍会触发完整解析(占槽、消耗
// cookies 寿命),用户侧表现为"所有 YouTube 视频都放不了 + 搜索也变慢"。熔断在
// 连续 N 次基础设施失败后快失败一段时间,并写入 /healthz,让单台电视的连续
// 重试不再放大成全站不可用;冷却后放行一个探测请求,成功即恢复。
var (
	youtubeCircuitThreshold = 3
	youtubeCircuitCooldown  = 60 * time.Second
)

type youtubeCircuitBreaker struct {
	mu                  sync.Mutex
	consecutiveFailures int
	open                bool
	openedAt            time.Time
	lastReason          string
	lastFailureAt       time.Time
}

func newYouTubeCircuitBreaker() *youtubeCircuitBreaker {
	return &youtubeCircuitBreaker{}
}

// allow 判断是否放行上游调用;熔断打开且未到冷却期时返回剩余等待时间。
// 冷却到期后自动进入半开状态:放行一个探测请求,成功会关闭熔断,失败会重新打开。
func (b *youtubeCircuitBreaker) allow(now time.Time) (bool, time.Duration) {
	if b == nil {
		return true, 0
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.open {
		return true, 0
	}
	elapsed := now.Sub(b.openedAt)
	if elapsed >= youtubeCircuitCooldown {
		b.open = false
		b.consecutiveFailures = 0
		return true, 0
	}
	return false, youtubeCircuitCooldown - elapsed
}

func (b *youtubeCircuitBreaker) recordSuccess() {
	if b == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	b.consecutiveFailures = 0
	b.open = false
	b.lastReason = ""
}

func (b *youtubeCircuitBreaker) recordInfraFailure(reason string, now time.Time) {
	if b == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	b.consecutiveFailures++
	b.lastReason = reason
	b.lastFailureAt = now
	if b.consecutiveFailures >= youtubeCircuitThreshold {
		b.open = true
		b.openedAt = now
	}
}

// snapshot 是 /healthz 里的 youtube 状态(部署后可直接判断"是不是上游挂了")。
func (b *youtubeCircuitBreaker) snapshot(now time.Time) map[string]any {
	state := "closed"
	retryAfter := 0
	consecutive := 0
	lastReason := ""
	if b != nil {
		b.mu.Lock()
		consecutive = b.consecutiveFailures
		lastReason = b.lastReason
		if b.open {
			state = "open"
			remaining := youtubeCircuitCooldown - now.Sub(b.openedAt)
			if remaining > 0 {
				retryAfter = int(remaining.Round(time.Second) / time.Second)
			}
		}
		b.mu.Unlock()
	}
	return map[string]any{
		"circuit":             state,
		"consecutiveFailures": consecutive,
		"lastReason":          lastReason,
		"retryAfterSeconds":   retryAfter,
	}
}

// youtubeCircuit 懒初始化每台 mediaServer 的熔断器(测试构造的字面量无需关心)。
func (s *mediaServer) youtubeCircuit() *youtubeCircuitBreaker {
	s.youtubeBreakerOnce.Do(func() {
		s.youtubeBreaker = newYouTubeCircuitBreaker()
	})
	return s.youtubeBreaker
}

// writeYouTubeUnavailable 把基础设施类失败映射为「服务暂不可用」:503 + Retry-After。
// 与「视频受限」的 502 区分开,App 可提示稍后重试而不是让用户换视频。
func (s *mediaServer) writeYouTubeUnavailable(w http.ResponseWriter, code, message string) {
	w.Header().Set("Retry-After", strconv.Itoa(youtubeInfraRetryAfterSeconds))
	s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: code, Message: message})
}

// writeYouTubeCircuitOpen 在熔断打开时快失败,并给出剩余冷却时间。
func (s *mediaServer) writeYouTubeCircuitOpen(w http.ResponseWriter, retryAfter time.Duration, code, message string) {
	seconds := int(retryAfter.Round(time.Second) / time.Second)
	if seconds < 1 {
		seconds = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(seconds))
	s.writeError(w, &apiError{Status: http.StatusServiceUnavailable, Code: code, Message: message})
}

const youtubeInfraRetryAfterSeconds = 15
