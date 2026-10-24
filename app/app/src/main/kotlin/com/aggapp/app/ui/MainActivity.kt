package com.aggapp.app.ui

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aggapp.app.ui.theme.AggTheme
import com.aggapp.app.update.AppUpdateEffect
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            AggTheme {
                val lightStatusBar = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                val lightNavigationBar = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightStatusBar
                        isAppearanceLightNavigationBars = lightNavigationBar
                    }
                }
                AppUpdateEffect()
                val navController = rememberNavController()
                AggScaffold(navController)
            }
        }
    }
}

@Composable
fun AggScaffold(navController: NavHostController) {
    val tabs = listOf(
        LiquidNavigationItem("idcode", "身份码", Icons.Filled.Home),
        LiquidNavigationItem("express", "快递", Icons.Filled.LocalShipping),
        LiquidNavigationItem("mine", "我的", Icons.Filled.Person),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "express"

    AppWindowLayout(content = { bottomContentPadding ->
        NavHost(
            navController = navController,
            startDestination = "express",
            modifier = Modifier.fillMaxSize(),
            // 快速淡入淡出，替换默认约 700ms 的转场（默认转场叠加重组会明显卡顿）
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable("idcode") { IdCodeScreen(hiltViewModel<MainViewModel>(), bottomContentPadding) }
            composable("express") { ExpressScreen(bottomContentPadding = bottomContentPadding) }
            composable("mine") { MineScreen(bottomContentPadding = bottomContentPadding) }
        }
    }, navigation = { backdrop ->
        LiquidBottomNavigation(
            items = tabs,
            selectedRoute = current,
            onRouteSelected = { route ->
                if (current != route) {
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            backdrop = backdrop,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        )
    })
}
