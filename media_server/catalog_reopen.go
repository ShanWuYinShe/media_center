package main

import (
	"context"
	"log"
	"time"
)

// catalog 状态机(原子读,后台可自愈重开):
//
// 旧实现只在启动时 OpenCatalog 一次,失败后 catalogErr 永久置位——进程不会
// 退出(compose 不会重启健康检查失败的容器),一次瞬时磁盘/权限/锁冲突就会让
// 目录永久不可用,只能人工重启。这里把 catalog 指针放进原子槽,后台按退避
// 重开,成功后原子替换,读路径完全无锁(不引入每请求 RWMutex)。
// catalogReopenDelays 是后台重开的退避序列(变量便于测试缩短)。
var catalogReopenDelays = []time.Duration{
	30 * time.Second,
	1 * time.Minute,
	2 * time.Minute,
	5 * time.Minute,
}

// catalogHandle 返回当前可用的 catalog;nil 表示未配置或暂时打不开。
// 兼容测试直接构造 mediaServer{catalog: c} 的写法(生产路径只走原子槽)。
func (s *mediaServer) catalogHandle() *Catalog {
	if c := s.catalogPtr.Load(); c != nil {
		return c
	}
	return s.catalog
}

// catalogErrorMessage 返回目录不可用的原因;nil 表示不处于错误态。
func (s *mediaServer) catalogErrorMessage() error {
	if v := s.catalogErrText.Load(); v != nil {
		if msg, ok := v.(string); ok && msg != "" {
			return &catalogUnavailableError{msg: msg}
		}
	}
	return s.catalogErr
}

// catalogHealth 返回 /healthz 的 catalog 取值:ok/disabled/error。
func (s *mediaServer) catalogHealth() string {
	if s.catalogErrorMessage() != nil {
		return "error"
	}
	if s.catalogHandle() == nil {
		return "disabled"
	}
	return "ok"
}

// catalogUnavailableError 是错误态下的轻量 error(只用于日志/探针文案)。
type catalogUnavailableError struct{ msg string }

func (e *catalogUnavailableError) Error() string { return e.msg }

func (s *mediaServer) setCatalogReady(c *Catalog) {
	s.catalogPtr.Store(c)
	s.catalogErrText.Store("")
}

// setCatalogFailed 只写原子错误槽:catalogErr 字段仅保留给测试在构造时注入,
// 运行期不再写它(否则后台重开 goroutine 与 handler 读会产生 data race)。
func (s *mediaServer) setCatalogFailed(err error) {
	if err != nil {
		s.catalogErrText.Store(err.Error())
	} else {
		s.catalogErrText.Store("")
	}
}

// startCatalogReopenLoop 在启动期打开失败后后台重试,成功后原子替换目录。
// 进程退出即停止;重开成功前公开端点一直返回 503(可重试),不会被客户端
// 当成"空目录"缓存。
func (s *mediaServer) startCatalogReopenLoop(path string) {
	go func() {
		for attempt := 0; ; attempt++ {
			delay := catalogReopenDelays[len(catalogReopenDelays)-1]
			if attempt < len(catalogReopenDelays) {
				delay = catalogReopenDelays[attempt]
			}
			time.Sleep(delay)
			catalog, err := OpenCatalog(path)
			if err != nil {
				log.Printf("catalog reopen attempt %d failed: %v", attempt+1, err)
				continue
			}
			s.setCatalogReady(catalog)
			log.Printf("catalog reopened after %d attempt(s): %s", attempt+1, path)
			return
		}
	}()
}

// catalogRequestContext 给没有显式 ctx 的调用方兜底(后台预热/管理路径)。
func catalogRequestContext(ctx context.Context) context.Context {
	if ctx == nil {
		return context.Background()
	}
	return ctx
}
