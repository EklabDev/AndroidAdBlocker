package com.eklab.adblocker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eklab.adblocker.ui.apps.AppsScreen
import com.eklab.adblocker.ui.connections.ConnectionsScreen
import com.eklab.adblocker.ui.home.HomeScreen
import com.eklab.adblocker.ui.rules.RulesScreen

object Routes {
    const val HOME = "home"
    const val CONNECTIONS = "connections"
    const val APPS = "apps"
    const val RULES = "rules"
}

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination(Routes.HOME, "Home", Icons.Filled.Home),
    Destination(Routes.CONNECTIONS, "Connections", Icons.Filled.SwapVert),
    Destination(Routes.APPS, "Apps", Icons.Filled.Apps),
    Destination(Routes.RULES, "Rules", Icons.Filled.Gavel),
)

/** Single-activity root: bottom navigation bar plus the four top-level screens. */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { AppBottomBar(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) { HomeScreen() }
            composable(Routes.CONNECTIONS) { ConnectionsScreen() }
            composable(Routes.APPS) { AppsScreen() }
            composable(Routes.RULES) { RulesScreen() }
        }
    }
}

@Composable
private fun AppBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}
