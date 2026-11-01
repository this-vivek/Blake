package com.blake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blake.app.ui.theme.BlakeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlakeTheme {
                // Scaffold owns inset handling (via bottomBar/innerPadding) for edge-to-edge,
                // so the root Surface doesn't need its own windowInsetsPadding.
                Surface(modifier = Modifier.fillMaxSize()) {
                    BlakeApp()
                }
            }
        }
    }
}

private data class NavDestination(val route: String, val label: String, val glyph: String)

// Plain text glyphs instead of a vector icon library for now — real icons can follow once
// the nav shell itself is confirmed stable.
private val DESTINATIONS = listOf(
    NavDestination("today", "Today", "⌂"),
    NavDestination("timeline", "Timeline", "☰"),
    NavDestination("insights", "Insights", "ⓘ"),
)

private fun NavHostController.navigateSingleTopTo(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlakeApp() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blake") },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                DESTINATIONS.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { navController.navigateSingleTopTo(dest.route) },
                        icon = { Text(dest.glyph, style = MaterialTheme.typography.titleMedium) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("today") { TodayScreen() }
            composable("timeline") { TimelineScreen() }
            composable("insights") { InsightsScreen() }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
