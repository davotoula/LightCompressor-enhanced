package com.davotoula.lce

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davotoula.lce.navigation.LceNavHost
import com.davotoula.lce.data.ThemePreferences
import com.davotoula.lce.ui.theme.LceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUris = extractSharedUris(intent)

        setContent {
            val appContext = LocalContext.current.applicationContext
            val themePreferences = remember { ThemePreferences(appContext) }
            val darkThemeOverride by themePreferences.darkThemeOverride
                .collectAsStateWithLifecycle(initialValue = null)
            val isSystemDark = isSystemInDarkTheme()
            val isDarkTheme = darkThemeOverride ?: isSystemDark
            val scope = rememberCoroutineScope()

            LceTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()
                LceNavHost(
                    navController = navController,
                    initialVideoUris = sharedUris,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        val nextTheme = !(darkThemeOverride ?: isSystemDark)
                        scope.launch {
                            themePreferences.setDarkThemeOverride(nextTheme)
                        }
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                } ?: emptyList()
            }
            else -> emptyList()
        }
    }
}
