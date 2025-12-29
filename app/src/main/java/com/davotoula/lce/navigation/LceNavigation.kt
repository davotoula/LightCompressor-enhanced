package com.davotoula.lce.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.davotoula.lce.ui.main.MainScreen
import com.davotoula.lce.ui.player.PlayerScreen

/**
 * Sealed class representing all navigation routes in the LCE app.
 *
 * Each route is a distinct destination in the app's navigation graph.
 * Using a sealed class ensures type-safe navigation and compile-time
 * verification of all possible routes.
 *
 * @property route The string representation of the route used by Navigation Compose
 */
sealed class LceRoute(val route: String) {
    /**
     * Main screen route - the home screen of the app where users can
     * select videos and configure compression settings.
     */
    data object Main : LceRoute("main")

    /**
     * Player screen route - displays the video player for compressed videos.
     * Takes a videoPath parameter which is URL-encoded.
     */
    data object Player : LceRoute("player/{videoPath}") {
        /**
         * Creates a navigation route with the encoded video path.
         *
         * @param videoPath The file path to the video to play
         * @return The complete route string with the encoded video path
         */
        fun createRoute(videoPath: String): String {
            return "player/${Uri.encode(videoPath)}"
        }
    }
}

/**
 * The main navigation host for the LCE app.
 *
 * This composable sets up the navigation graph for the entire app,
 * defining all screens and how they connect to each other.
 *
 * @param navController The navigation controller to manage navigation state
 * @param initialVideoUris Videos shared via Android "Share to" intent
 */
@Composable
fun LceNavHost(
    navController: NavHostController,
    initialVideoUris: List<Uri> = emptyList(),
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = LceRoute.Main.route
    ) {
        composable(LceRoute.Main.route) {
            MainScreen(
                initialVideoUris = initialVideoUris,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onNavigateToPlayer = { videoPath ->
                    navController.navigate(LceRoute.Player.createRoute(videoPath))
                }
            )
        }

        composable(
            route = LceRoute.Player.route,
            arguments = listOf(
                navArgument("videoPath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val videoPath = backStackEntry.arguments?.getString("videoPath")?.let {
                Uri.decode(it)
            } ?: return@composable

            PlayerScreen(
                videoPath = videoPath,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
