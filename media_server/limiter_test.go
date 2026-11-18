package main

import (
	"strconv"
	"testing"
	"time"
)

// TestIPWindowLimiterCapacityGuard 锁定限流表容量守卫:表满时新 key 被拒、
// 已有 key 判定不受影响、过期条目可被清理腾位(IPv6 源地址旋转下的内存
// 无界增长回归)。
func TestIPWindowLimiterCapacityGuard(t *testing.T) {
	l := newIPWindowLimiter(2, time.Minute)
	for i := 0; i < maxLimiterEntries; i++ {
		if !l.allow(keyFor(i)) {
			t.Fatalf("key %d 在表未满时应被放行", i)
		}
	}
	// 表已满:新 key 拒绝(清理后仍满)。
	if l.allow("new-ip-after-full") {
		t.Fatal("表满后新 key 应被拒绝")
	}
	// 已有 key 的判定不受容量守卫影响:limit=2,第二次请求仍在限内。
	if !l.allow(keyFor(0)) {
		t.Fatal("已有 key 的正常请求不应被容量守卫拒绝")
	}
	// 过期腾位:窗口过期后旧条目可被清理,新 key 恢复放行。
	for k, e := range l.entries {
		e.start = time.Now().Add(-2 * time.Minute)
		_ = k
	}
	if !l.allow("new-ip-after-expiry") {
		t.Fatal("过期条目清理腾位后,新 key 应被放行")
	}
}

func keyFor(i int) string {
	// 构造互不重复的 key(整数文本即可,无需真实 IP)。
	return "ip-" + strconv.Itoa(i)
}

// TestIPWindowLimiterPeriodicSweep 锁定周期清理:低流量场景下,过期条目不会
// 长期滞留到表满才被容量守卫回收(64M 容器内存预算)。
func TestIPWindowLimiterPeriodicSweep(t *testing.T) {
	l := newIPWindowLimiter(10, time.Minute)
	for i := 0; i < 50; i++ {
		if !l.allow(keyFor(i)) {
			t.Fatalf("key %d 在表未满时应被放行", i)
		}
	}
	// 老化所有条目与上次清理时间:模拟一分钟无流量后再来新客户端。
	l.mu.Lock()
	for _, e := range l.entries {
		e.start = time.Now().Add(-2 * time.Minute)
	}
	l.lastSweep = time.Now().Add(-2 * time.Minute)
	l.mu.Unlock()

	if !l.allow("fresh-ip-after-idle") {
		t.Fatal("周期清理后新 key 应被放行")
	}
	l.mu.Lock()
	n := len(l.entries)
	l.mu.Unlock()
	if n != 1 {
		t.Fatalf("过期条目应被周期清理,期望只剩 1 条,实际 %d", n)
	}
}
