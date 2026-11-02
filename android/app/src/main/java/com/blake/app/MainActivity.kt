package com.blake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blake.app.ui.theme.BlakeIcons
import com.blake.app.ui.theme.BlakeTheme
import com.blake.app.ui.theme.blake
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

private data class NavDestination(val route: String, val label: String, val icon: ImageVector)

private val LEFT_TABS = listOf(
    NavDestination("today", "Today", BlakeIcons.Today),
    NavDestination("timeline", "Timeline", BlakeIcons.Timeline),
)
private val RIGHT_TABS = listOf(
    NavDestination("insights", "Insights", BlakeIcons.Insights),
    NavDestination("settings", "Settings", BlakeIcons.Settings),
)

/** Tabs are plain destinations: picking one never restores stale saved state. Today is home,
 * so picking it pops everything above it (Workout, Session, Unmatched). */
private fun NavHostController.navigateSingleTopTo(route: String) {
    if (route == "today") {
        if (!popBackStack("today", inclusive = false)) navigate("today") { launchSingleTop = true }
        return
    }
    navigate(route) {
        popUpTo("today") { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
private fun NavTab(dest: NavDestination, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
        Icon(dest.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            dest.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/** Flat bar: Today, Timeline, a round "+" that opens Log anything, Insights, Settings. */
@Composable
private fun BlakeNavBar(currentRoute: String?, onNavigate: (String) -> Unit, onLog: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.blake.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LEFT_TABS.forEach { NavTab(it, currentRoute == it.route, { onNavigate(it.route) }, Modifier.weight(1f)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onLog),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        BlakeIcons.Plus,
                        contentDescription = "Log anything",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            RIGHT_TABS.forEach { NavTab(it, currentRoute == it.route, { onNavigate(it.route) }, Modifier.weight(1f)) }
        }
    }
}

@Composable
fun BlakeApp() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // Activity-scoped so the Log sheet and the Today screen share one chat state.
    val todayVm: TodayViewModel = viewModel()
    var showLog by remember { mutableStateOf(false) }

    // The session is full screen with its own close button.
    val hideBar = currentRoute == "session"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!hideBar) {
                BlakeNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { navController.navigateSingleTopTo(it) },
                    onLog = { showLog = true },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(if (hideBar) PaddingValues(0.dp) else innerPadding),
        ) {
            composable("today") {
                TodayScreen(
                    onNavigateToWorkout = { navController.navigate("workout") },
                    onStartSession = { navController.navigate("session") },
                    vm = todayVm,
                )
            }
            composable("workout") {
                WorkoutScreen(
                    onStartSession = { exercises ->
                        WorkoutSessionStore.pendingExercises = exercises
                        WorkoutSessionStore.isProgramSession = false
                        navController.navigate("session")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("session") {
                SessionScreen(onFinish = { navController.popBackStack() })
            }
            composable("timeline") { TimelineScreen() }
            composable("insights") { InsightsScreen() }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToUnmatched = { navController.navigate("unmatched") },
                    onDataReset = { todayVm.clearLocalState() },
                )
            }
            composable("unmatched") {
                UnmatchedScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    if (showLog) {
        LogSheet(vm = todayVm, onDismiss = { showLog = false })
    }
}
