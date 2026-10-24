package com.aggapp.app.ui

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AggTheme {
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

    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
    var navigationHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomContentPadding = with(density) { navigationHeightPx.toDp() }

    Box(Modifier.fillMaxSize().background(background)) {
        NavHost(
            navController = navController,
            startDestination = "express",
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(
                    // Capture only page content; sampling the glass itself would recurse.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.layerBackdrop(backdrop)
                    else Modifier,
                ),
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
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { navigationHeightPx = it.height }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
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
        }
    }
}
