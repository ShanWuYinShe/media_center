package com.example.mediacenter.net

import java.net.InetAddress
import java.util.regex.Pattern

/**
 * Cloudflare 优选 IP 的纯逻辑部分(候选解析、校验、合并、排序、host 匹配)。
 *
 * 与 media_app(normal 变体)的同名实现保持同一语义:服务端下发候选池，
 * **客户端自己测速**决定顺序。放在 legacy 包内而不是共享模块，是因为两个
 * 变体是独立 Gradle 工程(单包安装、minSdk 不同)，共享源码需要额外模块配置，
 * 收益不抵复杂度。
 */
internal object PreferredIpPool {
    /** 优选 IP 只对这个域名生效,其它域名一律退回系统 DNS(零副作用)。 */
    const val TARGET_HOST = "media.chaoset.com"

    /** 候选池上限:老盒子线程/fd 紧张，测速批量不宜过大。 */
    const val MAX_CANDIDATES = 16

    /** 内置兜底池:服务端下发失败时仍能加速(Cloudflare 官方 anycast IPv4 段)。 */
    val BUILTIN: List<String> = listOf(
        "103.31.4.1",
        "104.26.0.1",
        "104.16.128.1",
        "104.19.0.1",
        "104.20.0.1",
        "104.16.192.1",
        "172.66.0.1",
        "104.22.0.1",
    )

    private val IPV4_PATTERN: Pattern = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    /** 从任意文本里抽 IPv4(服务端下发 JSON，历史上也用过纯文本列表)。 */
    fun parseRemoteList(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val matcher = IPV4_PATTERN.matcher(text)
        while (matcher.find()) {
            val token = matcher.group()
            if (!isValidIpv4(token)) continue
            result.add(token)
            if (result.size >= MAX_CANDIDATES) break
        }
        return result.toList()
    }

    /** 严格校验 IPv4 字面量:四段 0..255，不允许前导零(避免被当成八进制)。 */
    fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            if (part.length > 1 && part[0] == '0') return false
            val number = part.toIntOrNull() ?: return false
            if (number !in 0..255) return false
        }
        return true
    }

    /** 合并服务端池与内置兜底池:服务端在前，内置补充，去重后截断。 */
    fun merge(remote: List<String>, builtin: List<String> = BUILTIN): List<String> {
        val merged = LinkedHashSet<String>()
        for (ip in remote) if (isValidIpv4(ip)) merged.add(ip)
        for (ip in builtin) if (isValidIpv4(ip)) merged.add(ip)
        val result = ArrayList<String>(merged.size)
        for (ip in merged) {
            result.add(ip)
            if (result.size >= MAX_CANDIDATES) break
        }
        return result
    }

    /** 是否为需要走优选 IP 的目标 host(含同名子域)。 */
    fun matchesHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized == TARGET_HOST || normalized.endsWith("." + TARGET_HOST)
    }

    /** 已测速 IP 的一条结果:延迟毫秒 + 是否可用。 */
    data class Probe(val ip: String, val latencyMs: Long, val reachable: Boolean)

    /** 按延迟升序排序:不可用的排最后，延迟相同保持原顺序(稳定)。 */
    fun rank(probes: List<Probe>): List<String> {
        val sorted = ArrayList(probes)
        sorted.sortWith(compareBy({ if (it.reachable) 0 else 1 }, { it.latencyMs }))
        return sorted.map { it.ip }
    }

    /** 把 IP 字面量转成 InetAddress:非法/失败项跳过(字面量解析不触发 DNS)。 */
    fun toAddresses(ips: List<String>): List<InetAddress> {
        val addresses = ArrayList<InetAddress>(ips.size)
        for (ip in ips) {
            if (!isValidIpv4(ip)) continue
            val address = try { InetAddress.getByName(ip) } catch (error: Exception) { null } ?: continue
            addresses.add(address)
        }
        return addresses
    }

    /** 把候选 IP 列表序列化成 SharedPreferences 里的存储形态(每行一个)。 */
    fun encode(ips: List<String>): String = ips.joinToString("\n")
}
