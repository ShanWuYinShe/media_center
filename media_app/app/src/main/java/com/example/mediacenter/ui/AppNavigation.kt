package com.example.mediacenter.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mediacenter.data.VideoCache
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.detail.DetailScreen
import com.example.mediacenter.ui.home.HomeScreen
import com.example.mediacenter.ui.player.PlayerScreen
import com.example.mediacenter.ui.search.SearchScreen

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{videoId}"
    const val PLAYER = "player/{videoId}"
    const val SEARCH = "search"

    fun detail(videoId: String) = "detail/$videoId"
    fun player(videoId: String) = "player/$videoId"
}

private fun navigateToDetail(video: VideoSummary, navController: androidx.navigation.NavController) {
    VideoCache.set(video)
    navController.navigate(Routes.detail(video.id))
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoClick = { navigateToDetail(it, navController) },
                onSearchClick = { navController.navigate(Routes.SEARCH) },
            )
        }
        composable(Routes.DETAIL) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            DetailScreen(
                videoId = videoId,
                onPlay = { navController.navigate(Routes.player(videoId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PLAYER) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            PlayerScreen(videoId = videoId, onBack = { navController.popBackStack() })
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onVideoClick = { navigateToDetail(it, navController) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
