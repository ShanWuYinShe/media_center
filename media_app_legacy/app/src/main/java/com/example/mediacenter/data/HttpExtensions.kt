package com.example.mediacenter.data

/**
 * 判断 HTTP 状态码是否属于瞬时/可重试错误。
 *
 * 覆盖：
 * - 408 Request Timeout（服务端繁忙未能处理）
 * - 425 Too Early（TLS 1.3 early data 被拒绝，正常重试即可）
 * - 429 Too Many Requests（限流，退避后重试）
 * - 5xx（服务端/网关错误，重试可能恢复）
 *
 * 不包含 400-407、409-424、426-428、430-499（业务错误、认证失败等，
 * 需要用户操作或不同处理）。
 */
fun Int.isTransientHttpStatusCode(): Boolean =
    this == 408 || this == 425 || this == 429 || this in 500..599

/**
 * 将 HTTP 状态码翻译成面向用户的中文提示，避免在界面上暴露 "HTTP 502" 这类原始技术信息。
 * 仅在服务端没有返回可读错误文案时作为兜底使用。
 */
fun Int.toFriendlyHttpMessage(): String = when {
    this in 500..599 -> "服务暂时繁忙，请稍后重试"
    this == 408 || this == 425 || this == 429 -> "网络有点拥堵，请稍后重试"
    this == 401 || this == 403 -> "暂时无法访问该内容"
    this == 404 -> "没有找到相关内容"
    this in 400..499 -> "请求出错了，请稍后重试"
    else -> "加载失败，请稍后重试"
}
