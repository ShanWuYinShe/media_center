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
