package com.example.mediacenter.ui.theme


enum class HomeFocusArea {
    Search,
    Category,
    Filter,
    Video,
    Hero,
}

sealed class HomeFocusTarget {
    object Search : HomeFocusTarget()
    object Provider : HomeFocusTarget()
    object Settings : HomeFocusTarget()
    object HeroPlay : HomeFocusTarget()
    object HeroDetails : HomeFocusTarget()
    data class Category(val id: String) : HomeFocusTarget()
    /** 时长筛选 chip(DurationFilter.id):"all"/"short"/"medium"/"long"。 */
    data class Filter(val id: String) : HomeFocusTarget()
    data class Video(val id: String) : HomeFocusTarget()

    fun viewTag(): String {
        return when (this) {
            Search -> "home:search"
            Provider -> "home:provider"
            Settings -> "home:settings"
            HeroPlay -> "home:hero:play"
            HeroDetails -> "home:hero:details"
            is Category -> "home:category:$id"
            is Filter -> "home:filter:$id"
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

val SEARCH_HOT_KEYWORDS = listOf(
    "动作", "喜剧", "科幻", "恐怖", "爱情", "悬疑",
    "国产", "港片", "好莱坞", "纪录", "战争", "动画",
)

val YOUTUBE_SEARCH_HOT_KEYWORDS = listOf(
    "音乐", "纪录片", "科技", "旅行", "美食", "学习", "游戏", "电影",
)
