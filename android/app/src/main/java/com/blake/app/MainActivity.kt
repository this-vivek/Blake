package com.blake.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blake.app.ui.theme.BlakeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel : ViewModel() {
    var responseText by mutableStateOf("")
    var isLoading by mutableStateOf(false)

    fun send(context: Context, text: String, imageBytes: ByteArray?) {
        val api = Prefs.apiFor(context)

        isLoading = true
        responseText = ""
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.chat(text, imageBytes) }
            isLoading = false
            responseText = result.fold(
                onSuccess = { it },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }
}

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

// "log" is temporary — removed once P1.5 docks the input bar on Today directly.
// Plain text glyphs instead of a vector icon library for now — real icons can follow once
// the nav shell itself is confirmed stable.
private val DESTINATIONS = listOf(
    NavDestination("today", "Today", "⌂"),
    NavDestination("log", "Log", "✎"),
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
            composable("log") { ChatScreen() }
            composable("timeline") { HistoryScreen() }
            composable("insights") { InsightsScreen() }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Log", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Log this...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickImage.launch("image/*") }) {
                Text(if (imageUri == null) "Attach photo" else "Photo attached")
            }
            if (imageUri != null) {
                TextButton(onClick = { imageUri = null }) { Text("Remove") }
            }
        }

        Button(
            onClick = {
                val bytes = imageUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                vm.send(context, text, bytes)
            },
            enabled = !vm.isLoading && (text.isNotBlank() || imageUri != null),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vm.isLoading) "Sending (server may be waking up)..." else "Send")
        }

        if (vm.responseText.isNotBlank()) {
            Divider()
            Text(vm.responseText)
        }
    }
}
