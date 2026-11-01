package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.ui.theme.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val MODULES = listOf("meals", "workouts", "expenses", "tasks")

class HistoryViewModel : ViewModel() {
    var summaryText by mutableStateOf("Loading summary...")
    var selectedModule by mutableStateOf(MODULES.first())
    var entries by mutableStateOf<List<String>>(emptyList())
    var isLoading by mutableStateOf(false)

    fun loadSummary(context: Context) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.summary() }
            summaryText = result.fold(
                onSuccess = { formatSummary(it) },
                onFailure = { "Couldn't load summary: ${it.message}" }
            )
        }
    }

    fun loadHistory(context: Context, module: String) {
        selectedModule = module
        isLoading = true
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.history(module) }
            isLoading = false
            entries = result.fold(
                onSuccess = { formatEvents(it) },
                onFailure = { listOf("Error: ${it.message}") }
            )
        }
    }
}

private fun formatSummary(json: String): String {
    val obj = JSONObject(json)
    val parts = mutableListOf<String>()
    obj.optJSONObject("meals")?.optJSONObject("today")?.let {
        parts.add("Meals today: ${Format.kcal(it.optDouble("kcal", 0.0))}")
    }
    obj.optJSONObject("expenses")?.optJSONObject("today")?.let {
        parts.add("Spent today: ${Format.currency(it.optDouble("total", 0.0))}")
    }
    obj.optJSONObject("tasks")?.optJSONArray("open_tasks")?.let {
        parts.add("Open tasks: ${it.length()}")
    }
    return if (parts.isEmpty()) "No data yet" else parts.joinToString("   |   ")
}

private fun formatEvents(json: String): List<String> {
    val events: JSONArray = JSONObject(json).optJSONArray("events") ?: JSONArray()
    val out = mutableListOf<String>()
    for (i in 0 until events.length()) {
        val e = events.getJSONObject(i)
        val kind = e.optString("kind")
        val payload = e.optJSONObject("payload") ?: JSONObject()
        out.add(
            when (kind) {
                "meal" -> {
                    val total = payload.optJSONObject("total")
                    "${payload.optString("meal")}: ${Format.kcal(total?.optDouble("kcal", 0.0) ?: 0.0)}"
                }
                "set" -> "${payload.optString("exercise")} ${Format.weightKg(payload.optDouble("weight_kg"))}" +
                    " x${payload.optInt("reps")} (e1RM ${Format.weightKg(payload.optDouble("e1rm"))})"
                "expense" -> "${payload.optString("category")}: ${Format.currency(payload.optDouble("amount"))}" +
                    (payload.optString("note").takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                "task" -> "To-do: ${payload.optString("title")}"
                "task_done" -> "Done: ${payload.optString("title")}"
                else -> payload.toString()
            }
        )
    }
    return if (out.isEmpty()) listOf("Nothing logged yet") else out
}

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.loadSummary(context)
        vm.loadHistory(context, vm.selectedModule)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today", style = MaterialTheme.typography.titleMedium)
        Text(vm.summaryText, modifier = Modifier.padding(vertical = 8.dp))

        Divider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MODULES.forEach { module ->
                FilterChip(
                    selected = vm.selectedModule == module,
                    onClick = { vm.loadHistory(context, module) },
                    label = { Text(module) }
                )
            }
        }

        if (vm.isLoading) {
            Text("Loading...")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.entries) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(entry, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
