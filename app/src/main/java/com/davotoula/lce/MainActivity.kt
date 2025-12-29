package com.davotoula.lce

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.davotoula.lce.navigation.LceNavHost
import com.davotoula.lce.ui.theme.LceTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LceTheme {
                val navController = rememberNavController()
                LceNavHost(navController = navController)
            }
        }
    }
}
