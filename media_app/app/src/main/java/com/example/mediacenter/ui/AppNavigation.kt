package com.example.mediacenter.ui

import android.net.Uri
import android.os.Bundle
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import com.example.mediacenter.ui.detail.DetailScreen
import com.example.mediacenter.ui.home.HomeScreen
import com.example.mediacenter.ui.player.PlayerScreen
import com.example.mediacenter.ui.search.SearchScreen
import com.example.mediacenter.ui.settings.SettingsScreen
import com.example.mediacenter.ui.theme.BackgroundDark
import com.example.mediacenter.util.DevicePerformance
import kotlinx.serialization.json.Json

// 页面转场:低端盒子直接关掉动画(转场本身就是弱 GPU 的掉帧源);高配机用
// 短促 fade + 轻微位移,给导航方向感但不拖沓。TV 遥控器操作频繁,时长 ≤200ms。
private val navEnter: EnterTransition =
    if (DevicePerformance.isLowEnd) EnterTransition.None else fadeIn(tween(200))
private val navExit: ExitTransition =
    if (DevicePerformance.isLowEnd) ExitTransition.None else fadeOut(tween(160))
private val detailEnter: EnterTransition =
    if (DevicePerformance.isLowEnd) {
        EnterTransition.None
    } else {
        fadeIn(tween(200)) + slideInVertically(tween(200)) { height -> height / 24 }
    }

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{video}"
    const val PLAYER = "player/{videoId}?title={title}&page={page}&poster={poster}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    private const val ARG_VIDEO = "video"
    private const val ARG_VIDEO_ID = "videoId"
    private const val ARG_TITLE = "title"

    fun detail(video: VideoSummary) =
        "detail/${videoSummaryType.serializeAsValue(video)}"

    fun player(videoId: String, title: String, page: Int = 0, posterUrl: String? = null): String {
        val base = "player/${Uri.encode(videoId, "")}?title=${Uri.encode(title, "")}"
        val pagePart = if (page > 0) "&page=$page" else ""
        val posterPart =
            if (!posterUrl.isNullOrBlank()) "&poster=${Uri.encode(posterUrl, "")}" else ""
        return base + pagePart + posterPart
    }
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
    // 转场:NavHost 默认是较长的交叉淡入淡出,10ft 观看距离下偏拖沓,
    // 换页时上一屏会明显压住下一屏。统一收敛为短淡入淡出(navEnter/navExit),
    // 详情页加极轻位移、播放页更快;低端机直接无动画(见文件顶部转场定义)。
    // 底色:交叉淡化中段两屏都半透明,不铺底会透出宿主 Activity 的主题
    // 窗底(Theme.Material 默认深灰,并非设计基线 #111111);各页面根部都
    // 未自绘背景,故在 NavHost 统一铺 BackgroundDark,静置底色一并归位。
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize().background(BackgroundDark),
        enterTransition = { navEnter },
        exitTransition = { navExit },
        popEnterTransition = { navEnter },
        popExitTransition = { navExit },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoClick = { video -> navController.navigate(Routes.detail(video)) },
                onPlayVideo = { video, page ->
                    navController.navigate(
                        Routes.player(
                            video.id,
                            video.title,
                            page,
                            video.thumbnailUrlLarge ?: video.thumbnailUrl,
                        ),
                    )
                },
                onSearchClick = { navController.navigate(Routes.SEARCH) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("video") { type = videoSummaryType }),
            // 详情页从卡片进入:淡入 + 极轻的向上位移(1/24 屏高),比纯 fade 有
            // 方向感但依然克制;低端机退化为无动画。
            enterTransition = { detailEnter },
            exitTransition = { navExit },
            popEnterTransition = { navEnter },
            popExitTransition = { navExit },
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
                onPlay = { page ->
                    navController.navigate(
                        Routes.player(
                            video.id,
                            video.title,
                            page,
                            video.thumbnailUrlLarge ?: video.thumbnailUrl,
                        ),
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PLAYER,
            // 播放页进入要更快:fade 160/120ms,减少"按了播放还在过渡"的体感。
            enterTransition = { if (DevicePerformance.isLowEnd) EnterTransition.None else fadeIn(tween(160)) },
            exitTransition = { if (DevicePerformance.isLowEnd) ExitTransition.None else fadeOut(tween(120)) },
            popEnterTransition = { navEnter },
            popExitTransition = { navExit },
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
                // 播放页首帧前的封面(URL 已由服务端按详情尺寸下发);空值则不显示。
                navArgument("poster") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            val title = backStackEntry.arguments?.getString("title") ?: ""
            val page = backStackEntry.arguments?.getInt("page") ?: 0
            val posterUrl = backStackEntry.arguments?.getString("poster").orEmpty()
                .ifBlank { null }
            PlayerScreen(
                videoId = videoId,
                videoTitle = title,
                initialPage = page,
                posterUrl = posterUrl,
                onBack = { navController.popBackStack() },
                // 合集连播现在在播放页内部无缝切集(不重建页面/播放服务),
                // 不再通过导航替换播放页。
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
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
