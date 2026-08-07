package com.example.mediacenter.ui.theme


enum class HomeFocusArea {
    Search,
    Category,
    Video,
}

sealed class HomeFocusTarget {
    object Search : HomeFocusTarget()
    data class Category(val id: String) : HomeFocusTarget()
    data class Video(val id: String) : HomeFocusTarget()

    fun viewTag(): String {
        return when (this) {
            Search -> "home:search"
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

val SEARCH_HOT_KEYWORDS = listOf(
    "动作", "喜剧", "科幻", "恐怖", "爱情", "悬疑",
    "国产", "港片", "好莱坞", "纪录", "战争", "动画",
)
