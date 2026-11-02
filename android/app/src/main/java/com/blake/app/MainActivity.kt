package com.blake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.blake.app.ui.theme.BlakeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    // Free-tier hosting cold-starts after ~15min idle; pinging on foreground (not waiting on
    // the response) means that cold boot runs while already-cached data is on screen, instead
    // of being eaten by whichever request the user happens to trigger first.
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            Prefs.apiFor(this@MainActivity).health()
        }
    }
}

private data class NavDestination(val route: String, val label: String, val glyph: String)

// Plain text glyphs instead of a vector icon library for now — real icons can follow once
// the nav shell itself is confirmed stable.
private val DESTINATIONS = listOf(
    NavDestination("today", "Today", "⌂"),
    NavDestination("workout", "Workout", "🏋️"),
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

    val isFullScreenRoute = currentRoute == "session"

    Scaffold(
        topBar = {
            if (!isFullScreenRoute) {
                TopAppBar(
                    title = { Text("Blake") },
                    actions = {
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Text("⚙", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!isFullScreenRoute) {
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
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(if (isFullScreenRoute) PaddingValues(0.dp) else innerPadding),
        ) {
            composable("today") { TodayScreen() }
            composable("workout") {
                WorkoutScreen(onStartSession = { exercises ->
                    WorkoutSessionStore.pendingExercises = exercises
                    navController.navigate("session")
                })
            }
            composable("session") {
                SessionScreen(onFinish = { navController.popBackStack() })
            }
            composable("timeline") { TimelineScreen() }
            composable("insights") { InsightsScreen() }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
