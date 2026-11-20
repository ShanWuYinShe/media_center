package com.example.mediacenter.net

import java.net.InetAddress

/**
 * Cloudflare 优选 IP 的**纯逻辑**部分:候选池解析、延迟排序、host 匹配。
 *
 * **背景(实测)**:服务端挂在 Cloudflare 后面，国内运营商 DNS 会把
 * `media.chaoset.com` 解析到绕路、甚至被 QoS 的 anycast 节点——同一域名 15 次
 * 请求里 8 次直接连不上，成功时首字节 3.7s;而绑定优选 IP(实测 103.31.4.1)后
 * 15/15 成功、首字节 1.0s。所以客户端必须自己测速挑 IP，不能等 DNS 决定节点。
 *
 * 候选池由服务端 `/api/v1/preferred-ips` 下发(服务端在墙外，能稳定拉取公共
 * 优选源并挑选)，本文件只放可在 JVM 单测里跑的纯函数，Android 侧的拉取/测速/
 * 持久化见 [PreferredIpStore]。
 */
internal object PreferredIpPool {
    /** 优选 IP 只对这个域名生效,其它域名一律退回系统 DNS(零副作用)。 */
    const val TARGET_HOST = "media.chaoset.com"

    /** 候选池上限:测速是启动期后台任务,池子太大只会拖长首次可用时间。 */
    const val MAX_CANDIDATES = 24

    /**
     * 内置兜底池:服务端下发失败(断网/首启动/端点异常)时仍能加速。
     * 取自 Cloudflare 官方 anycast IPv4 段里实测稳定的节点，只是兜底。
     */
    val BUILTIN: List<String> = listOf(
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
    )

    /**
     * 从任意文本里抽 IPv4:服务端下发的是 JSON，但历史上也用过纯文本列表，
     * 统一按正则抓取，锚定不到结构化字段时也不至于整份丢弃。
     */
    fun parseRemoteList(text: String): List<String> {
        val result = LinkedHashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            for (token in IPV4_PATTERN.findAll(line).map { it.value }) {
                if (!isValidIpv4(token)) continue
                result.add(token)
                if (result.size >= MAX_CANDIDATES) return result.toList()
            }
        }
        return result.toList()
    }

    private val IPV4_PATTERN = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    /** 严格校验 IPv4 字面量:四段 0..255，不允许前导零(避免被当成八进制)。 */
    fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            if (part.length > 1 && part[0] == '0') return false
            val number = part.toIntOrNull() ?: return false
            if (number !in 0..255) return false
        }
        return true
    }

    /**
     * 合并服务端池与内置兜底池:服务端在前，内置补充，去重后截断。
     */
    fun merge(remote: List<String>, builtin: List<String> = BUILTIN): List<String> {
        val merged = LinkedHashSet<String>()
        for (ip in remote) if (isValidIpv4(ip)) merged.add(ip)
        for (ip in builtin) if (isValidIpv4(ip)) merged.add(ip)
        return merged.take(MAX_CANDIDATES)
    }

    /** 是否为需要走优选 IP 的目标 host(含同名子域)。 */
    fun matchesHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized == TARGET_HOST || normalized.endsWith(".$TARGET_HOST")
    }

    /** 已测速 IP 的一条结果:延迟毫秒 + 是否可用。 */
    data class Probe(val ip: String, val latencyMs: Long, val reachable: Boolean)

    /**
     * 按延迟升序排序:不可用的排最后，延迟相同保持原顺序(稳定)。
     */
    fun rank(probes: List<Probe>): List<String> =
        probes.sortedWith(compareBy({ if (it.reachable) 0 else 1 }, { it.latencyMs })).map { it.ip }

    /** 把 IP 字面量转成 [InetAddress]:非法/失败项跳过(字面量解析不触发 DNS)。 */
    fun toAddresses(ips: List<String>): List<InetAddress> {
        val addresses = ArrayList<InetAddress>(ips.size)
        for (ip in ips) {
            if (!isValidIpv4(ip)) continue
            val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
            addresses.add(address)
        }
        return addresses
    }

    /** 把候选 IP 列表序列化成 SharedPreferences 里的存储形态(每行一个)。 */
    fun encode(ips: List<String>): String = ips.joinToString("\n")
}
