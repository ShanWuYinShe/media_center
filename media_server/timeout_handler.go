package main

import (
	"context"
	"log"
	"net/http"
	"runtime/debug"
	"sync"
	"time"
)

// apiTimeoutHandler 是 http.TimeoutHandler 的替代实现:超时时返回统一的 JSON
// 错误包络(503 TIMEOUT)而不是纯文本。
//
// 为什么不用标准库:http.TimeoutHandler 的超时分支只写 "服务超时" 文本,
// Content-Type 也不是 application/json;App 与其它调用方按统一错误包络解析
// code/message,拿到纯文本时只能退化成"网络错误"提示,且无法区分"服务超时"
// 与"服务不可用"。其余语义与标准库一致:handler 写入缓冲,超时丢弃缓冲回 503,
// 未超时原样复制状态码/响应头/响应体。
//
// 大文件下载(/api/app/download)在 ServeHTTP 里先行匹配、不经此包裹(流式发送,
// 缓冲整份响应既浪费内存也会截断传输)。
type apiTimeoutHandler struct {
	timeout time.Duration
	handler http.Handler
}

func newAPITimeoutHandler(timeout time.Duration, handler http.Handler) http.Handler {
	return &apiTimeoutHandler{timeout: timeout, handler: handler}
}

func (h *apiTimeoutHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), h.timeout)
	defer cancel()

	bw := &timeoutBuffer{header: make(http.Header)}
	done := make(chan struct{})
	var panicValue any
	go func() {
		defer func() {
			if p := recover(); p != nil {
				panicValue = p
				log.Printf("api timeout handler: panic recovered: %v\n%s", p, debug.Stack())
			}
			close(done)
		}()
		h.handler.ServeHTTP(bw, r.WithContext(ctx))
	}()

	select {
	case <-done:
		if panicValue != nil {
			writeErrorEnvelope(w, &apiError{
				Status:  http.StatusInternalServerError,
				Code:    "INTERNAL_ERROR",
				Message: "服务端内部错误。",
			})
			return
		}
		bw.writeTo(w)
	case <-ctx.Done():
		// 超时或客户端断开:丢弃 handler 的缓冲输出,写统一 JSON 错误。
		// handler goroutine 可能仍在运行,但它只写已丢弃的缓冲,不会碰真实响应。
		writeErrorEnvelope(w, &apiError{
			Status:  http.StatusServiceUnavailable,
			Code:    "TIMEOUT",
			Message: "服务超时，请稍后重试。",
		})
	}
}

// timeoutBuffer 收集 handler 的响应,供超时后整体丢弃或正常时复制。
// 超时后 handler 仍可能并发写入,因此 Write/WriteHeader 加锁
// (go test -race 会覆盖超时后晚到写入的竞态)。Header() 只有 handler 自己读,
// 主 goroutine 只在 handler 结束(或放弃复制)后访问,无需加锁。
type timeoutBuffer struct {
	mu          sync.Mutex
	header      http.Header
	status      int
	wroteHeader bool
	body        []byte
}

func (b *timeoutBuffer) Header() http.Header { return b.header }

func (b *timeoutBuffer) WriteHeader(code int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.wroteHeader {
		return
	}
	b.status = code
	b.wroteHeader = true
}

func (b *timeoutBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.wroteHeader {
		b.status = http.StatusOK
		b.wroteHeader = true
	}
	b.body = append(b.body, p...)
	return len(p), nil
}

func (b *timeoutBuffer) writeTo(w http.ResponseWriter) {
	b.mu.Lock()
	defer b.mu.Unlock()
	dst := w.Header()
	for k, vs := range b.header {
		dst[k] = append([]string(nil), vs...)
	}
	status := b.status
	if status == 0 {
		status = http.StatusOK
	}
	w.WriteHeader(status)
	if len(b.body) > 0 {
		_, _ = w.Write(b.body)
	}
}
