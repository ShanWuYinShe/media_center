package com.example.mediacenter.ui

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.category.CategoryVideosScreen
import com.example.mediacenter.ui.detail.DetailScreen
import com.example.mediacenter.ui.home.HomeScreen
import com.example.mediacenter.ui.player.PlayerScreen
import com.example.mediacenter.ui.search.SearchScreen
import com.example.mediacenter.ui.settings.SettingsScreen
import kotlinx.serialization.json.Json

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{video}"
    const val PLAYER = "player/{videoId}?title={title}"
    const val SEARCH = "search"
    const val CATEGORY = "category/{categoryId}?title={title}"
    const val SETTINGS = "settings"

    private const val ARG_VIDEO = "video"
    private const val ARG_VIDEO_ID = "videoId"
    private const val ARG_TITLE = "title"
    private const val ARG_CATEGORY_ID = "categoryId"

    fun detail(video: VideoSummary) =
        "detail/${videoSummaryType.serializeAsValue(video)}"

    fun player(videoId: String, title: String) =
        "player/${Uri.encode(videoId, "")}?title=${Uri.encode(title, "")}"

    fun category(categoryId: String, title: String) =
        "category/${Uri.encode(categoryId, "")}?title=${Uri.encode(title, "")}"
}

// 自定义 NavType:把整个 VideoSummary 序列化为 route 参数,随导航保存。
// 这样详情页不再依赖全局单例缓存——进程重建、播放页返回后都能恢复数据。
private val videoJson = Json { ignoreUnknownKeys = true }

private val videoSummaryType = object : NavType<VideoSummary>(isNullableAllowed = false) {
    override fun get(bundle: Bundle, key: String): VideoSummary? =
        bundle.getString(key)?.let { raw ->
            runCatching { videoJson.decodeFromString(VideoSummary.serializer(), raw) }.getOrNull()
        }

    override fun parseValue(value: String): VideoSummary =
        videoJson.decodeFromString(VideoSummary.serializer(), value)

    override fun put(bundle: Bundle, key: String, value: VideoSummary) {
        bundle.putString(key, videoJson.encodeToString(VideoSummary.serializer(), value))
    }

    override fun serializeAsValue(value: VideoSummary): String =
        Uri.encode(videoJson.encodeToString(VideoSummary.serializer(), value), "")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoClick = { video -> navController.navigate(Routes.detail(video)) },
                onSearchClick = { navController.navigate(Routes.SEARCH) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onSeeAll = { categoryId, title ->
                    navController.navigate(Routes.category(categoryId, title))
                },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("video") { type = videoSummaryType }),
        ) { backStackEntry ->
            val video = backStackEntry.arguments?.getString("video")?.let { raw ->
                runCatching { videoJson.decodeFromString(VideoSummary.serializer(), raw) }.getOrNull()
            } ?: return@composable
            DetailScreen(
                video = video,
                onPlay = { navController.navigate(Routes.player(video.id, video.title)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            val title = backStackEntry.arguments?.getString("title") ?: ""
            PlayerScreen(
                videoId = videoId,
                videoTitle = title,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onVideoClick = { video -> navController.navigate(Routes.detail(video)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.CATEGORY,
            arguments = listOf(
                navArgument("categoryId") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId") ?: return@composable
            val title = backStackEntry.arguments?.getString("title") ?: ""
            CategoryVideosScreen(
                categoryId = categoryId,
                title = title,
                onVideoClick = { video -> navController.navigate(Routes.detail(video)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
