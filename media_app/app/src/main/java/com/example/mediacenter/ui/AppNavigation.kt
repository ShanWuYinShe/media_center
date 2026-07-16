package com.example.mediacenter.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController

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

    }
}
