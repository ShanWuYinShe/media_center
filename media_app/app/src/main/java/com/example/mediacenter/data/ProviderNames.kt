package com.example.mediacenter.data

/**
 * provider 的展示名兜底。服务端 `/providers` 会下发 name;这里用于只有
 * provider id 的场景(详情页来源标签、搜索页当前源提示),与服务端
 * `providerDisplayName` 保持同名同值。
 */
fun providerDisplayName(providerID: String): String = when (providerID) {
    "bilibili" -> "B站"
    "youtube" -> "YouTube"
    "internet-archive" -> "Internet Archive"
    else -> providerID
}
