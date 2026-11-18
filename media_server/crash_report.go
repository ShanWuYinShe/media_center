package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// CrashReport 是 App 端崩溃上报的请求体。只落盘不做分析——诊断在发布侧进行。
type CrashReport struct {
	PackageName    string `json:"packageName"`
	AppVersionName string `json:"appVersionName"`
	AppVersionCode int    `json:"appVersionCode"`
	OsVersion      string `json:"osVersion"`
	Device         string `json:"device"`
	StackTrace     string `json:"stackTrace"`
	OccurredAt     string `json:"occurredAt"`
}

const (
	maxCrashBodyBytes = 512 * 1024
	maxCrashLogBytes  = 8 * 1024 * 1024
)

// crash 端点限流:同一 IP 每分钟最多 10 条。崩溃上报是公开写盘端点,正常频率
// 极低(每台设备每次启动至多 1 条),限流只用来挡垃圾/滥用请求,阈值很宽松。
const crashReportPerMinuteLimit = 10

// crashLogLock 串行化崩溃日志写入(多进程容器内同一路径追加)。
var crashLogLock sync.Mutex

// maxLimiterEntries 是单个限流表的条目上限。走 Cloudflare 时 clientIP 是真实
// 客户端 IPv6,攻击者可用海量源地址制造新 key;新鲜攻击 IP 会让 entries 无界
// 增长,所以按内存预算设硬上限。真实部署容器只有 64M(见
// deploy/docker-compose.yml),而服务内有 5 个 ipWindowLimiter(崩溃/凭证/
// 目录搜索/youtube 搜索/resolve),不能按「各 10 万条」设计:一条 map entry 含
// IPv6 key 字符串(~40B)+窗口结构+map 开销约 100B,5×10 万条最坏 >40MB,再叠加
// modernc SQLite 堆与 Go 运行时余量,会在 64M 容器里被 OOMKill。
// 单表 16384 条(≈1.6MB/表,5 表 ≤8MB)对家用场景足够;表满后新 key 直接按
// 限流拒绝(已有条目判定不受影响),IPv6 轮换攻击下内存仍有界。
const maxLimiterEntries = 16384

// ipWindowLimiter 是 per-IP 固定窗口限流器(崩溃上报/凭证/搜索等端点共用)。
type ipWindowLimiter struct {
	mu        sync.Mutex
	limit     int
	window    time.Duration
	entries   map[string]*ipWindow
	lastSweep time.Time
}

type ipWindow struct {
	start time.Time
	count int
}

func newIPWindowLimiter(limit int, window time.Duration) *ipWindowLimiter {
	return &ipWindowLimiter{limit: limit, window: window, entries: make(map[string]*ipWindow)}
}

func (l *ipWindowLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	// 周期性清理过期条目(每个窗口最多一次):正常流量下也保持稳态表体积,
	// 不必等表满触发容量守卫才扫描(低流量时旧 IP 条目会长期占内存)。
	if now.Sub(l.lastSweep) >= l.window {
		for k, e := range l.entries {
			if now.Sub(e.start) >= l.window {
				delete(l.entries, k)
			}
		}
		l.lastSweep = now
	}
	w, ok := l.entries[key]
	if !ok || now.Sub(w.start) >= l.window {
		// 容量守卫:表满时先清理过期条目,仍满则拒绝新 key(不影响已有
		// 条目的判定),消除 IPv6 源地址旋转下的无界内存增长。
		if len(l.entries) >= maxLimiterEntries {
			for k, e := range l.entries {
				if now.Sub(e.start) >= l.window {
					delete(l.entries, k)
				}
			}
			if len(l.entries) >= maxLimiterEntries {
				return false
			}
		}
		l.entries[key] = &ipWindow{start: now, count: 1}
		return true
	}
	if w.count >= l.limit {
		return false
	}
	w.count++
	return true
}

// defaultCrashLimiter 是进程级默认限流器:main() 构造 mediaServer 时已显式接线
// crashLimiter 字段,这里仅作 nil 兜底(便于测试构造省略该字段的场景)。
var defaultCrashLimiter = newIPWindowLimiter(crashReportPerMinuteLimit, time.Minute)

// handleCrashReport 接收 App 崩溃上报,按 JSONL 追加写入
// {CrashReportDir}/crash.log,超过上限时滚动为 crash.log.1。
// 请求体非法时返回 400,落盘失败返回 500;正常返回 {"status":"ok"}。
func (s *mediaServer) handleCrashReport(w http.ResponseWriter, r *http.Request) {
	limiter := s.crashLimiter
	if limiter == nil {
		limiter = defaultCrashLimiter
	}
	if !limiter.allow(clientIPFromRequest(r)) {
		s.writeError(w, &apiError{Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "上报过于频繁,请稍后再试。"})
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxCrashBodyBytes))
	if err != nil {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "请求体读取失败。"})
		return
	}
	var report CrashReport
	if err := json.Unmarshal(body, &report); err != nil || strings.TrimSpace(report.StackTrace) == "" {
		s.writeError(w, &apiError{Status: http.StatusBadRequest, Code: "BAD_REQUEST", Message: "上报内容不合法。"})
		return
	}
	if report.OccurredAt == "" {
		report.OccurredAt = time.Now().UTC().Format(time.RFC3339)
	}
	line, err := json.Marshal(report)
	if err != nil {
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		return
	}
	if err := appendCrashReport(s.config.CrashReportDir, line); err != nil {
		log.Printf("crash report write: %v", err)
		s.writeError(w, &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func appendCrashReport(dir string, line []byte) error {
	if dir == "" {
		dir = "data/crash-reports"
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	crashLogLock.Lock()
	defer crashLogLock.Unlock()
	target := filepath.Join(dir, "crash.log")
	if fi, err := os.Stat(target); err == nil && fi.Size() >= maxCrashLogBytes {
		_ = os.Remove(target + ".1")
		_ = os.Rename(target, target+".1")
	}
	// 0640:崩溃栈可能含路径/设备等敏感信息,挂载卷对同宿主其他用户不可读。
	f, err := os.OpenFile(target, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.Write(append(line, '\n'))
	return err
}
