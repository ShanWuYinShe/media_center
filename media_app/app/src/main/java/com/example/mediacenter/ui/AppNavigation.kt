package com.example.mediacenter.ui

import android.net.Uri
import android.os.Bundle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
import com.example.mediacenter.ui.theme.BackgroundDark
import kotlinx.serialization.json.Json

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{video}"
    const val PLAYER = "player/{videoId}?title={title}&page={page}"
    const val SEARCH = "search"
    const val CATEGORY = "category/{categoryId}?title={title}"
    const val SETTINGS = "settings"

    private const val ARG_VIDEO = "video"
    private const val ARG_VIDEO_ID = "videoId"
    private const val ARG_TITLE = "title"
    private const val ARG_CATEGORY_ID = "categoryId"

    fun detail(video: VideoSummary) =
        "detail/${videoSummaryType.serializeAsValue(video)}"

    fun player(videoId: String, title: String, page: Int = 0) =
        "player/${Uri.encode(videoId, "")}?title=${Uri.encode(title, "")}" +
            if (page > 0) "&page=$page" else ""

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
    // 转场:NavHost 默认是 700ms 交叉淡入淡出,10ft 观看距离下偏拖沓,
    // 换页时上一屏会明显压住下一屏。收敛为短淡入淡出并显式给出
    // popEnter/popExit,保证进入与返回同节奏,保持 TV 上克制的动效。
    // 底色:交叉淡化中段两屏都半透明,不铺底会透出宿主 Activity 的主题
    // 窗底(Theme.Material 默认深灰,并非设计基线 #111111);各页面根部都
    // 未自绘背景,故在 NavHost 统一铺 BackgroundDark,静置底色一并归位。
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize().background(BackgroundDark),
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoClick = { video -> navController.navigate(Routes.detail(video)) },
                onContinueVideoClick = { video, page ->
                    navController.navigate(Routes.player(video.id, video.title, page))
                },
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
            } ?: run {
                // 仅 saved state 损坏时发生:退回上一页,不渲染无提示的空白页
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            DetailScreen(
                video = video,
                onPlay = { page -> navController.navigate(Routes.player(video.id, video.title, page)) },
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
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 0
                },
            ),
        ) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            val title = backStackEntry.arguments?.getString("title") ?: ""
            val page = backStackEntry.arguments?.getInt("page") ?: 0
            PlayerScreen(
                videoId = videoId,
                videoTitle = title,
                initialPage = page,
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
