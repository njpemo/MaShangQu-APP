package com.aggapp.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aggapp.app.ui.theme.AggTheme
import com.aggapp.app.update.AppUpdateEffect
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

private data class TabItem(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AggScaffold(navController: NavHostController) {
    val tabs = listOf(
        TabItem("idcode", "身份码") { Icon(Icons.Filled.Home, null) },
        TabItem("express", "快递") { Icon(Icons.Filled.LocalShipping, null) },
        TabItem("mine", "我的") { Icon(Icons.Filled.Person, null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "idcode"

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            if (current != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = navController,
            startDestination = "idcode",
            modifier = Modifier.padding(pad),
            // 快速淡入淡出，替换默认约 700ms 的转场（默认转场期间叠加重组会明显卡顿）
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable("idcode") { IdCodeScreen(hiltViewModel<MainViewModel>()) }
            composable("express") { ExpressScreen() }
            composable("mine") { MineScreen() }
        }
    }
}
