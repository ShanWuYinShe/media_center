package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

// version 由构建期注入(-ldflags -X main.version,值为仓库 git describe),
// 经 /health 与启动日志暴露,未注入时为 "dev"。
var version = "dev"

// mediaServer 是目录服务:从 catalog(SQLite)提供 provider 列表、分类列表、
// 视频列表。播放地址解析与上游调用原则上由 App 端按 provider 自行处理(bilibili
// 调 B 站 API);唯一例外是 youtube provider 的两个实时端点(见 youtube.go):
// 搜索与播放解析均代理 vod_extractor(yt-dlp ytsearch 实时搜索)——YouTube
// 风控对抗依赖服务端集中更新 yt-dlp,不适合下沉到 App。
type mediaServer struct {
	config         AppConfig
	categories     []ContentCategory
	categoriesByID map[string]ContentCategory
	catalog        *Catalog
	// catalogErr 记录配置了 DB 但打开失败的原因:此时服务仍可响应(空目录),
	// 但 /health 会标记 degraded,避免"进程活着但全站空白"被监控漏掉。
	catalogErr   error
	crashLimiter *ipWindowLimiter
	// 凭证端点 per-IP 限流(独立于崩溃上报限流器)。
	credentialsLimiter *ipWindowLimiter
	// 搜索端点 per-IP 限流:公开匿名端点中最重的一个(count+list 两次全表
	// LIKE 扫描,单 SQLite 连接串行化),必须挡住脚本化滥用。
	searchLimiter *ipWindowLimiter
	// 搜索并发闸门:MaxOpenConns(1) 下搜索排队无收益,只会拖垮目录浏览
	// 主链路;满载快速 503,不让恶意并发灌满连接等待队列。
	searchGate chan struct{}
	// youtube 实时端点客户端与独立限流(yt-dlp 进程资源与 YouTube 风控,见 youtube.go)。
	yt                    *ytClient
	youtubeSearchLimiter  *ipWindowLimiter
	youtubeResolveLimiter *ipWindowLimiter

	// 凭证热加载:admin 生成 providerCredentials.json 后无需重启本服务。
	// credentialsMu 保护 (credentials, credsStat) 二元组;读路径先原子快照,
	// 仅在文件 mtime/size 变化时才重新解析(每请求最多一次 stat,解析失败
	// 保留旧值,下次变更再试)。
	credentialsMu   sync.Mutex
	credentials     []ProviderCredential
	credsStat       fs.FileInfo
	credentialsPath string
}

// currentCredentials 返回凭证快照;文件变化时懒重载。
func (s *mediaServer) currentCredentials() []ProviderCredential {
	s.credentialsMu.Lock()
	defer s.credentialsMu.Unlock()
	if s.credentialsPath == "" {
		return s.credentials
	}
	st, err := os.Stat(s.credentialsPath)
	if err != nil || sameFileStat(st, s.credsStat) {
		return s.credentials
	}
	creds, err := loadCredentials(s.credentialsPath)
	if err != nil {
		// 解析失败保留旧凭证(admin 侧原子写保证无半写文件,这里兜底
		// 网络文件系统的极端时序)。
		return s.credentials
	}
	s.credentials = creds
	s.credsStat = st
	log.Printf("reloaded %d provider credential(s) from %s", len(creds), s.credentialsPath)
	return s.credentials
}

func sameFileStat(a, b fs.FileInfo) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return a.ModTime().Equal(b.ModTime()) && a.Size() == b.Size()
}

// 凭证端点 per-IP 限流:正常 App 每次启动只拉一次凭证,阈值宽松但可挡匿名批量抓取。
const credentialsPerMinuteLimit = 30

// 搜索端点 per-IP 限流:正常 TV App 是提交式搜索,30/min 绰绰有余;/api/search
// 匿名可达且每次请求做两次全表 LIKE 扫描,是最重的公开端点,必须限流。
const searchPerMinuteLimit = 30

// 搜索并发上限:catalog 用单 SQLite 连接(SetMaxOpenConns(1)),并发搜索只会
// 在连接上排队并拖累全部公开 API;超载快速失败,保护目录浏览主链路。
const maxConcurrentSearches = 4

// defaultSearchLimiter/defaultSearchGate 是进程级默认防线:main() 构造时已显式
// 接线,这里仅作 nil 兜底(测试裸构造 mediaServer 时行为与生产一致),与
// crashLimiter 的兜底模式相同。
var (
	defaultSearchLimiter = newIPWindowLimiter(searchPerMinuteLimit, time.Minute)
	defaultSearchGate    = make(chan struct{}, maxConcurrentSearches)
)

// 搜索关键词最大字节数:超长一律拒绝,避免无界 query 驱动全表 LIKE 扫描。
const maxSearchQueryBytes = 128

func main() {
	catalogReview := flag.Bool("catalog-review", false, "审校分类维护数据库的 pending 候选(approve/reject)后退出")
	catalogReviewCategory := flag.String("catalog-review-category", "", "限定只审校某个分类 ID(默认全部)")
	catalogReviewDryRun := flag.Bool("catalog-review-dry-run", false, "只打印审校结果,不写库")
	catalogReviewReviewer := flag.String("catalog-review-reviewer", "", "审校人留痕(默认按模式自动填)")
	catalogConsolidate := flag.Bool("catalog-consolidate", false, "合并 catalog 中分 P 条目(_pN)为单一视频,删除冗余记录后退出")
	flag.Parse()

	config, err := loadAppConfig()
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	content, err := loadContentConfig(config.CategoriesPath)
	if err != nil {
		log.Fatalf("load categories: %v", err)
	}
	server := &mediaServer{
		config:                config,
		categories:            content.Categories,
		categoriesByID:        indexCategories(content.Categories),
		crashLimiter:          newIPWindowLimiter(crashReportPerMinuteLimit, time.Minute),
		credentialsLimiter:    newIPWindowLimiter(credentialsPerMinuteLimit, time.Minute),
		searchLimiter:         newIPWindowLimiter(searchPerMinuteLimit, time.Minute),
		searchGate:            make(chan struct{}, maxConcurrentSearches),
		yt:                    newYTClient(config.VODExtractorURL),
		youtubeSearchLimiter:  newIPWindowLimiter(youtubeSearchPerMinuteLimit, time.Minute),
		youtubeResolveLimiter: newIPWindowLimiter(youtubeResolvePerMinuteLimit, time.Minute),
	}
	if config.VODExtractorURL != "" {
		log.Printf("youtube resolve enabled (vod extractor at %s)", config.VODExtractorURL)
	}

	if config.CredentialsPath != "" {
		if creds, err := loadCredentials(config.CredentialsPath); err != nil {
			log.Printf("warning: load credentials %q failed: %v", config.CredentialsPath, err)
		} else {
			server.credentials = creds
			if st, err := os.Stat(config.CredentialsPath); err == nil {
				server.credsStat = st
			}
			log.Printf("loaded %d provider credential(s) from %s", len(creds), config.CredentialsPath)
		}
		server.credentialsPath = config.CredentialsPath
	}

	if config.CatalogDBPath != "" {
		if catalog, err := OpenCatalog(config.CatalogDBPath); err != nil {
			log.Printf("warning: open catalog %q failed: %v", config.CatalogDBPath, err)
			server.catalogErr = err
		} else {
			server.catalog = catalog
			defer catalog.Close()
		}
	}

	if *catalogReview {
		if server.catalog == nil {
			log.Fatalf("catalog review requires a catalog db (set MEDIA_SERVER_CATALOG_DB)")
		}
		opts := ReviewOptions{
			CategoryID: *catalogReviewCategory,
			DryRun:     *catalogReviewDryRun,
			Reviewer:   *catalogReviewReviewer,
		}
		log.Printf("reviewing catalog pending (db=%s, category=%q, dry-run=%t)…",
			config.CatalogDBPath, opts.CategoryID, opts.DryRun)
		if err := server.runCatalogReview(context.Background(), opts); err != nil {
			log.Fatalf("catalog review: %v", err)
		}
		return
	}

	if *catalogConsolidate {
		if server.catalog == nil {
			log.Fatalf("catalog consolidate requires a catalog db (set MEDIA_SERVER_CATALOG_DB)")
		}
		log.Printf("consolidating page entries in catalog (db=%s)…", config.CatalogDBPath)
		groups, deleted, created, err := server.catalog.ConsolidatePages()
		if err != nil {
			log.Fatalf("catalog consolidate: %v", err)
		}
		log.Printf("consolidation done: %d groups processed, %d _pN records deleted, %d base videos created", groups, deleted, created)
		return
	}

	addr := fmt.Sprintf("%s:%s", config.Host, config.Port)
	srv := &http.Server{
		Addr:              addr,
		Handler:           requestLogMiddleware(server),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		// WriteTimeout 不设:它是「从 request body 读完到写完响应」的硬截止,
		// 对 http.ServeFile 流式发送的大文件下载(/api/app/download)会在慢客户端
		// 下被强行截断。API 路由的超时保护改由 ServeHTTP 里的 TimeoutHandler 精确
		// 施加(下载端点绕过它),避免一刀切。
		IdleTimeout: 120 * time.Second,
	}
	log.Printf("media_server %s listening on %s", version, addr)

	// 优雅停机:收到 SIGTERM/SIGINT 后停止接收新连接,给进行中的请求最多
	// 10 秒收尾(否则 compose stop/重启会直接砍断在途请求)。
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("listen: %v", err)
		}
	}()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	log.Println("media_server shutting down…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("graceful shutdown: %v", err)
	}
}

// apiTimeout 是普通 API 路由的超时上限。http.Server.WriteTimeout 之所以置空,
// 是为了让大文件下载(/api/app/download)不被砍断;这里用 TimeoutHandler 把超时
// 保护精确施加在 API 路由上,下载端点在 ServeHTTP 里优先匹配、绕过此包裹。
const apiTimeout = 75 * time.Second

// apiV1Prefix 是独立站(media.chaoset.com)部署的对外规范业务前缀
// (web-service-conventions:业务接口一律 /api/v1/)。内部路由仍以 /api 书写,
// 入口处把 /api/v1/xxx 归一为 /api/xxx;旧 /api/xxx 保留为过渡期废弃别名
// (workwork nginx 反代与存量 App 在用),客户端全量切换后一并移除。
const apiV1Prefix = "/api/v1"

func (s *mediaServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// /api/v1 → /api 归一化:只改写路由用的 Path,Query 不受影响。
	// 非前缀路径(含旧 /api/* 与 /healthz、/internal/*)原样进入分发。
	if rest, ok := strings.CutPrefix(r.URL.Path, apiV1Prefix); ok {
		r.URL.Path = "/api" + rest
	}
	// 大文件下载绕过超时包裹:TimeoutHandler 会缓冲整个响应再写出,对流式发送
	// 的 APK 下载既没必要又浪费内存,且超时会截断传输。优先匹配后直接交给 handler。
	if r.Method == http.MethodGet && r.URL.Path == "/api/app/download" {
		s.handleAppDownload(w, r)
		return
	}
	http.TimeoutHandler(http.HandlerFunc(s.serveAPI), apiTimeout, "服务超时").ServeHTTP(w, r)
}

// serveAPI 处理除大文件下载外的全部路由。由 ServeHTTP 经 TimeoutHandler 包裹,
// 单个 API 请求超过 apiTimeout 即返回 503 并中断,防止慢查询/卡住的 handler 拖垮服务。
func (s *mediaServer) serveAPI(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && (r.URL.Path == "/health" || r.URL.Path == "/healthz" || r.URL.Path == "/api/health"):
		// /healthz 是独立站约定的无鉴权存活探针(nginx 直接探此路径);
		// /health 是容器 healthcheck 与既有调用方的原名;/api/health 为兼容保留。
		// 配置了 catalog DB 却打开失败时返回 503 + degraded:服务还能响应空目录,
		// 但这是"全站空白"的故障态,必须让探针/监控看见,而不是永远 status=ok。
		status := http.StatusOK
		payload := map[string]any{"status": "ok", "version": version}
		switch {
		case s.catalogErr != nil:
			status = http.StatusServiceUnavailable
			payload["status"] = "degraded"
			payload["catalog"] = "error"
		case s.catalog == nil:
			payload["catalog"] = "disabled"
		default:
			payload["catalog"] = "ok"
		}
		s.writeJSON(w, status, payload)
	case strings.HasPrefix(r.URL.Path, "/internal/"):
		s.handleInternal(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/providers":
		s.handleProviders(w)
	case r.Method == http.MethodGet && r.URL.Path == "/api/providers/credentials":
		s.handleProviderCredentials(w, r)
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/providers/"):
		s.handleProviderRoute(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/search":
		s.handleSearch(w, r)
	case r.Method == http.MethodGet && r.URL.Path == "/api/app/update":
		s.handleAppUpdate(w, r)
	case r.Method == http.MethodPost && r.URL.Path == "/api/crash/report":
		s.handleCrashReport(w, r)
	default:
		s.writeError(w, &apiError{
			Status:  http.StatusNotFound,
			Code:    "NOT_FOUND",
			Message: "请求的资源不存在。",
		})
	}
}

func (s *mediaServer) writeJSON(w http.ResponseWriter, status int, payload any) {
	writeJSONStatus(w, status, payload)
}

func (s *mediaServer) writeError(w http.ResponseWriter, err error) {
	writeErrorEnvelope(w, err)
}

// writeJSONStatus 与 writeErrorEnvelope 是错误/响应包络的自由函数形态:
// requestLogMiddleware 的 panic 恢复路径没有 mediaServer 接收者,也要写同一
// 包络;方法形式只是它们的薄包装。
func writeJSONStatus(w http.ResponseWriter, status int, payload any) {
	body, err := json.Marshal(payload)
	if err != nil {
		log.Printf("writeJSON marshal: %v", err)
		writeErrorEnvelope(w, &apiError{
			Status:  http.StatusInternalServerError,
			Code:    "INTERNAL_ERROR",
			Message: "服务端内部错误。",
		})
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeErrorEnvelope(w http.ResponseWriter, err error) {
	var apiErr *apiError
	if !errors.As(err, &apiErr) {
		log.Printf("unhandled error: %v", err)
		apiErr = &apiError{Status: http.StatusInternalServerError, Code: "INTERNAL_ERROR", Message: "服务端内部错误。"}
	}
	writeJSONStatus(w, apiErr.Status, apiErrorEnvelope{Error: apiErrorBody{Code: apiErr.Code, Message: apiErr.Message}})
}

func indexCategories(categories []ContentCategory) map[string]ContentCategory {
	out := make(map[string]ContentCategory, len(categories))
	for _, category := range categories {
		out[category.ID] = category
	}
	return out
}
