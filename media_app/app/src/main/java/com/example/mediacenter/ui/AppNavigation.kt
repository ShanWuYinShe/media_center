package com.example.mediacenter.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mediacenter.ui.home.HomeScreen
import com.example.mediacenter.ui.player.PlayerScreen

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{videoId}"
    const val PLAYER = "player/{videoId}"
    const val SEARCH = "search"

    fun detail(videoId: String) = "detail/$videoId"
    fun player(videoId: String) = "player/$videoId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoClick = { navController.navigate(Routes.player(it)) },
                onSearchClick = { navController.navigate(Routes.SEARCH) },
            )
        }
        composable(Routes.PLAYER) { backStackEntry ->
            val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
            PlayerScreen(videoId = videoId, onBack = { navController.popBackStack() })
        }
    }
}
