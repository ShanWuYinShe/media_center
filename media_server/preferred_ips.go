package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

// preferred_ips.go — Cloudflare 优选 IP 候选池下发端点(GET /api/preferred-ips)。
//
// **为什么需要**:服务端挂在 Cloudflare 后面，国内运营商 DNS 常把 media 域名
// 解析到绕路或被 QoS 的 anycast 节点。实测同一域名走系统 DNS:15 次请求 8 次
// 直接建不了连接、成功时首字节 3.7s;绑定一个优选 IP 后 15/15 成功、首字节 1.0s。
//
// **职责划分**:测速必须由**客户端**做——只有客户端知道自己到各节点的真实 RTT，
// 而且 TV 所在网络与服务器出口完全不是一回事。服务端只负责提供一份「新鲜、
// 覆盖国内三大运营商、且确实属于 Cloudflare anycast 段」的候选池，这样换 IP
// 不用发版。公共优选源在境内直连普遍不可用(实测多为超时/被污染)，由服务器在
// 墙外拉取再下发给客户端，是唯一稳定路径。
const (
	// preferredIPsMaxItems 是下发条数上限:客户端要逐个测速，太多会拖长首次
	// 可用时间；24 个在 6 并发下约 4 轮，1~2s 内可完成。
	preferredIPsMaxItems = 24
	// preferredIPsTTL 是候选池缓存时长:优选 IP 质量以小时计，1 小时足够新鲜。
	preferredIPsTTL = time.Hour
	// preferredIPSourceTimeout 是单个公共源拉取超时:源站慢不能拖住刷新。
	preferredIPSourceTimeout = 8 * time.Second
	// preferredIPSourceBodyLimit 是单个源响应体读取上限(纯 IP 列表只有几 KB)。
	preferredIPSourceBodyLimit = 64 << 10
)

// preferredIPSeeds 是内置种子池，取自 Cloudflare 官方 anycast IPv4 段
// (cloudflare.com/ips-v4，与 deploy/nginx 的 set_real_ip_from 同源)。
// 它们既是公共源全部失败时的兜底，也是初始池子；最终顺序由客户端测速决定。
var preferredIPSeeds = []string{
	"103.31.4.1",
	"104.26.0.1",
	"104.16.128.1",
	"104.19.0.1",
	"104.20.0.1",
	"104.16.192.1",
	"172.66.0.1",
	"104.22.0.1",
	"104.21.5.97",
	"172.67.133.68",
	"104.25.34.117",
	"104.27.39.54",
}

// preferredIPSources 是公共优选 IP 源。只收「纯文本 IP 列表」形态:
// JSON 源各站字段互不兼容，解析器会被上游改版拖死；纯文本最多是某行格式变了，
// 正则直接跳过，容错空间最大。
var preferredIPSources = []string{
	"https://ipdb.api.030101.xyz/?type=bestcf",
	"https://cleanip.io/api/cf-best-ip",
}

// preferredIPv4Pattern 从任意文本里抽 IPv4 字面量(兼容纯列表、JSON、CSV、HTML)。
var preferredIPv4Pattern = regexp.MustCompile(`\b(?:\d{1,3}\.){3}\d{1,3}\b`)

// preferredIPsPayload 是下发结构。
type preferredIPsPayload struct {
	// Items 是按「种子优先、公共源随后」去重后的候选 IP 列表。
	Items []string `json:"items"`
	// UpdatedAt 是池子最近一次成功刷新的时间(RFC3339)，便于客户端判断新鲜度。
	UpdatedAt string `json:"updatedAt,omitempty"`
	// CacheSeconds 告诉客户端可以缓存多久，避免每次启动都打这个端点。
	CacheSeconds int `json:"cacheSeconds"`
}

// preferredIPPool 维护候选池内存状态:后台定期刷新，读路径只取快照。
type preferredIPPool struct {
	mu      sync.RWMutex
	items   []string
	updated time.Time
	once    sync.Once
}

func newPreferredIPPool() *preferredIPPool {
	pool := &preferredIPPool{}
	// 同步装入种子池:即使后台刷新还没跑完，端点立刻就有可下发内容。
	pool.items = append(pool.items, preferredIPSeeds...)
	pool.updated = time.Now()
	return pool
}

// snapshot 返回池子副本与更新时间。
func (p *preferredIPPool) snapshot() ([]string, time.Time) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	items := make([]string, len(p.items))
	copy(items, p.items)
	return items, p.updated
}

// start 启动后台刷新循环(幂等)。首次刷新延迟数秒，避开启动期其它初始化。
func (p *preferredIPPool) start(ctx context.Context) {
	p.once.Do(func() {
		go func() {
			select {
			case <-ctx.Done():
				return
			case <-time.After(5 * time.Second):
			}
			p.refresh(ctx)
			ticker := time.NewTicker(preferredIPsTTL)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					p.refresh(ctx)
				}
			}
		}()
	})
}

// refresh 拉取公共源 → 与种子合并去重 → 原子替换池子。
// 任何失败都保留旧池子(种子兜底)，绝不让端点空转。
func (p *preferredIPPool) refresh(ctx context.Context) {
	candidates, ok := p.collect(ctx)
	if !ok || len(candidates) == 0 {
		log.Printf("preferred ips: refresh collected no candidate, keeping previous pool")
		return
	}
	p.mu.Lock()
	p.items = candidates
	p.updated = time.Now()
	p.mu.Unlock()
	log.Printf("preferred ips: pool refreshed with %d candidate(s)", len(candidates))
}

// collect 汇总种子与公共源，按出现顺序去重并截断到上限。
// 返回 false 表示公共源全部失败(此时仍用种子池，不需要替换)。
func (p *preferredIPPool) collect(ctx context.Context) ([]string, bool) {
	seen := make(map[string]struct{}, preferredIPsMaxItems*2)
	collected := make([]string, 0, preferredIPsMaxItems)
	add := func(ip string) bool {
		if !isValidIPv4Literal(ip) {
			return false
		}
		if _, exists := seen[ip]; exists {
			return false
		}
		seen[ip] = struct{}{}
		collected = append(collected, ip)
		return len(collected) >= preferredIPsMaxItems
	}
	// 公共源在前:它们更新鲜，且包含国内三网实测节点。
	anySourceOK := false
	for _, source := range preferredIPSources {
		body, err := p.fetchSource(ctx, source)
		if err != nil {
			log.Printf("preferred ips: fetch %s failed: %v", source, err)
			continue
		}
		anySourceOK = true
		for _, ip := range preferredIPv4Pattern.FindAllString(body, preferredIPsMaxItems) {
			if add(ip) {
				return collected, true
			}
		}
	}
	// 种子兜底补齐。
	for _, ip := range preferredIPSeeds {
		if add(ip) {
			break
		}
	}
	return collected, anySourceOK
}

func (p *preferredIPPool) fetchSource(ctx context.Context, source string) (string, error) {
	reqCtx, cancel := context.WithTimeout(ctx, preferredIPSourceTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, source, nil)
	if err != nil {
		return "", err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, preferredIPSourceBodyLimit))
	if err != nil {
		return "", err
	}
	return string(body), nil
}

// isValidIPv4Literal 严格校验 IPv4 字面量(四段 0..255，拒绝前导零与 IPv6)。
func isValidIPv4Literal(value string) bool {
	parts := strings.Split(value, ".")
	if len(parts) != 4 {
		return false
	}
	for _, part := range parts {
		if part == "" || len(part) > 3 {
			return false
		}
		if len(part) > 1 && part[0] == '0' {
			return false
		}
		number := 0
		for _, ch := range part {
			if ch < '0' || ch > '9' {
				return false
			}
			number = number*10 + int(ch-'0')
		}
		if number > 255 {
			return false
		}
	}
	return true
}

// preferredIPsPayloadFor 构造下发响应(池子为空时也要给出种子，保证客户端有 IP 可用)。
func (s *mediaServer) preferredIPsPayloadFor() preferredIPsPayload {
	items, updated := []string(nil), time.Time{}
	if s.preferredIPs != nil {
		items, updated = s.preferredIPs.snapshot()
	}
	if len(items) == 0 {
		items = preferredIPSeeds
	}
	payload := preferredIPsPayload{
		Items:        items,
		CacheSeconds: int(preferredIPsTTL.Seconds()),
	}
	if !updated.IsZero() {
		payload.UpdatedAt = updated.UTC().Format(time.RFC3339)
	}
	return payload
}

// handlePreferredIPs 实现 GET /api/preferred-ips(Ap路径 /api/v1/preferred-ips)。
//
// 纯加速辅助端点:内容公开、无鉴权、不含敏感信息;带 Cache-Control 让边缘与
// 客户端都能缓存，客户端每次启动最多打一次。
func (s *mediaServer) handlePreferredIPs(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", fmt.Sprintf("public, max-age=%d", int(preferredIPsTTL.Seconds())/2))
	s.writeJSON(w, http.StatusOK, s.preferredIPsPayloadFor())
}
