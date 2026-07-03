package main

import (
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"
)

// requestLogMiddleware 记录每个进入服务的 HTTP 请求的结构化访问日志。
//
// **为什么需要它**:在此之前本服务零请求日志,完全依赖 nginx 反代日志。
// 但 nginx 看不到应用层(handler 内部错误、内部令牌请求、admin 直连
// /internal/* 调用),导致排查"客户端报网络错误但请求到底到没到服务端"
// 这类问题时无从下手。这里在应用层补上访问日志,与 nginx 互补。
//
// 记录字段:method、path、status、duration_ms、client_ip、bytes。
// 按 status 分级:2xx/3xx → Info,4xx → Warn,5xx → Error。
// 不记录请求体/响应体(隐私 + 体积)。
func requestLogMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)

		duration := time.Since(start).Milliseconds()
		// 健康检查高频低价值,降级为 Debug 避免刷屏。
		level := slog.LevelInfo
		switch {
		case r.URL.Path == "/health" || r.URL.Path == "/api/health":
			level = slog.LevelDebug
		case rec.status >= 500:
			level = slog.LevelError
		case rec.status >= 400:
			level = slog.LevelWarn
		}
		slog.Default().LogAttrs(r.Context(), level, "request",
			slog.String("method", r.Method),
			slog.String("path", r.URL.RequestURI()),
			slog.Int("status", rec.status),
			slog.Int64("duration_ms", duration),
			slog.String("client_ip", clientIPFromRequest(r)),
			slog.Int("bytes", rec.bytes),
			slog.String("user_agent", r.UserAgent()),
		)
	})
}

// statusRecorder 包裹 http.ResponseWriter 以捕获状态码与已写字节数。
type statusRecorder struct {
	http.ResponseWriter
	status     int
	bytes      int
	wroteHeader bool
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.wroteHeader {
		return
	}
	r.status = code
	r.wroteHeader = true
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if !r.wroteHeader {
		r.WriteHeader(http.StatusOK)
	}
	n, err := r.ResponseWriter.Write(b)
	r.bytes += n
	return n, err
}

// clientIPFromRequest 解析客户端真实 IP。
//
// media_server 部署在 internal_docker 私网,唯一调用方是 nginx(经
// X-Real-IP / X-Forwarded-For 传入真实客户端 IP)与 admin(直连)。
// 本服务是纯公开目录 API、无鉴权,client IP 仅作诊断用途,因此按
// 优先级读取转发头,不做 trusted-proxy 守卫(伪造无安全影响)。
func clientIPFromRequest(r *http.Request) string {
	if ip := strings.TrimSpace(r.Header.Get("X-Real-IP")); ip != "" {
		return ip
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if idx := strings.IndexByte(xff, ','); idx >= 0 {
			return strings.TrimSpace(xff[:idx])
		}
		return strings.TrimSpace(xff)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
