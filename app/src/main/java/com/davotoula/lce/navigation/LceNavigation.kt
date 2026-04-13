@file:Suppress("MatchingDeclarationName")

package com.davotoula.lce.navigation

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.davotoula.lce.ui.hls.HlsScreen
import com.davotoula.lce.ui.hls.HlsViewModel
import com.davotoula.lce.ui.main.MainScreen
import com.davotoula.lce.ui.player.PlayerScreen

/**
 * Sealed class representing all navigation routes in the LCE app.
 */
sealed class LceRoute(
    val route: String,
) {
    data object Main : LceRoute("main")

    data object Hls : LceRoute("hls")

    data object Player : LceRoute("player/{videoPath}") {
        fun createRoute(videoPath: String): String = "player/${Uri.encode(videoPath)}"
    }
}

@Composable
fun LceNavHost(
    navController: NavHostController,
    initialVideoUris: List<Uri> = emptyList(),
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    // Scope the HLS ViewModel to the hosting Activity so a running HLS
    // preparation survives back-navigation between Main and the HLS screen.
    val activity = LocalActivity.current as ComponentActivity
    val hlsViewModel: HlsViewModel = viewModel(viewModelStoreOwner = activity)

    NavHost(
        navController = navController,
        startDestination = LceRoute.Main.route,
    ) {
        composable(LceRoute.Main.route) {
            MainScreen(
                initialVideoUris = initialVideoUris,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                hlsViewModel = hlsViewModel,
                onNavigateToHls = { navController.navigate(LceRoute.Hls.route) },
                onNavigateToPlayer = { videoPath ->
                    navController.navigate(LceRoute.Player.createRoute(videoPath))
                },
            )
        }

        composable(LceRoute.Hls.route) {
            HlsScreen(
                viewModel = hlsViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToPlayer = { videoPath ->
                    navController.navigate(LceRoute.Player.createRoute(videoPath))
                },
            )
        }

        composable(
            route = LceRoute.Player.route,
            arguments =
                listOf(
                    navArgument("videoPath") { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val videoPath =
                backStackEntry.arguments?.getString("videoPath")?.let {
                    Uri.decode(it)
                } ?: return@composable

            PlayerScreen(
                videoPath = videoPath,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
