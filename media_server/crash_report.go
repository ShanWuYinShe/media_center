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

// ipWindowLimiter 是 per-IP 固定窗口限流器(崩溃上报/凭证端点共用)。
type ipWindowLimiter struct {
	mu      sync.Mutex
	limit   int
	window  time.Duration
	entries map[string]*ipWindow
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
	w, ok := l.entries[key]
	if !ok || now.Sub(w.start) >= l.window {
		l.entries[key] = &ipWindow{start: now, count: 1}
		// 惰性清理过期条目,防止长期运行下 map 无限增长
		if len(l.entries) > 1024 {
			for k, e := range l.entries {
				if now.Sub(e.start) >= l.window {
					delete(l.entries, k)
				}
			}
		}
		return true
	}
	if w.count >= l.limit {
		return false
	}
	w.count++
	return true
}

// defaultCrashLimiter 返回进程级默认限流器:mediaServer.crashLimiter 未显式
// 初始化(如某些测试构造路径)时兜底使用。
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
	f, err := os.OpenFile(target, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.Write(append(line, '\n'))
	return err
}
