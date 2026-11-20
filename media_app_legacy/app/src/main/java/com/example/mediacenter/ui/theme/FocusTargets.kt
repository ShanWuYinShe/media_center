package com.example.mediacenter.ui.theme


enum class HomeFocusArea {
    Search,
    Category,
    Video,
}

sealed class HomeFocusTarget {
    object Search : HomeFocusTarget()
    object Provider : HomeFocusTarget()
    object Settings : HomeFocusTarget()
    data class Category(val id: String) : HomeFocusTarget()
    data class Video(val id: String) : HomeFocusTarget()

    fun viewTag(): String {
        return when (this) {
            Search -> "home:search"
            Provider -> "home:provider"
            Settings -> "home:settings"
            is Category -> "home:category:$id"
            is Video -> "home:video:$id"
        }
    }
}

sealed class DetailFocusTarget {
    object Play : DetailFocusTarget()
    object Refresh : DetailFocusTarget()
    data class Option(val id: String) : DetailFocusTarget()
}

const val HomeFocusNoCategoryKey = "__no_category__"

// 面向 B 站全站搜索语义:泛类型词(动作/爱情等)在全站搜索下会命中大量
// 擦边内容(实测),家庭场景不合适——改用演员名/片名/曲艺类名词(线上
// 实测返回的均为电影正片、京剧舞台、相声等家庭友好内容)。
val SEARCH_HOT_KEYWORDS = listOf(
    "周星驰", "成龙", "李连杰", "经典电影", "老电影", "纪录片",
    "相声", "小品", "京剧", "春晚", "武侠", "战争片",
)

val YOUTUBE_SEARCH_HOT_KEYWORDS = listOf(
    "音乐", "纪录片", "科技", "旅行", "美食", "学习", "游戏", "电影",
)
