package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.GoalsCacheEntity
import com.blake.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SettingsViewModel : ViewModel() {
    var kcalGoal by mutableStateOf("")
    var proteinGoal by mutableStateOf("")
    var spendGoal by mutableStateOf("")
    var workoutDaysGoal by mutableStateOf("")
    var bodyweightGoal by mutableStateOf("")
    var goalsStatus by mutableStateOf("")
    var goalsLoading by mutableStateOf(false)

    private fun applyGoalsJson(json: String) {
        val obj = JSONObject(json)
        if (obj.has("kcal")) kcalGoal = obj.optDouble("kcal").toInt().toString()
        if (obj.has("protein_g")) proteinGoal = obj.optDouble("protein_g").toInt().toString()
        if (obj.has("daily_spend")) spendGoal = obj.optDouble("daily_spend").toInt().toString()
        if (obj.has("workout_days_per_week")) {
            workoutDaysGoal = obj.optInt("workout_days_per_week").toString()
        }
        if (obj.has("bodyweight_kg")) {
            bodyweightGoal = obj.optDouble("bodyweight_kg").toString()
        }
    }

    /** Cache-first: render the last-known goals from Room immediately (if any), then refresh
     * from the network and persist the fresh copy for next time. */
    fun loadGoals(context: Context) {
        goalsLoading = true
        viewModelScope.launch {
            val cached = BlakeDatabase.get(context).goalsDao().observe().first()
            if (cached != null) {
                applyGoalsJson(cached.json)
                goalsLoading = false
            }
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.getGoals() }
            goalsLoading = false
            result.onSuccess { json ->
                applyGoalsJson(json)
                BlakeDatabase.get(context).goalsDao().upsert(
                    GoalsCacheEntity(json = json, updatedAt = System.currentTimeMillis())
                )
            }
            result.onFailure { goalsStatus = "Couldn't load goals: ${it.message}" }
        }
    }

    var dataStatus by mutableStateOf("")

    /** Resets on the server, then clears everything cached or queued on the phone so a
     * pending offline write can't bring the data straight back. */
    fun resetData(context: Context, path: String, doneMessage: String, onDone: () -> Unit) {
        val api = Prefs.apiFor(context)
        dataStatus = "Resetting..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.rawRequest("POST", path, JSONObject().put("confirm", true).toString())
            }
            result.fold(
                onSuccess = {
                    val db = BlakeDatabase.get(context)
                    withContext(Dispatchers.IO) {
                        db.outboxDao().clearAll()
                        db.eventDao().clearAll()
                        db.todayDao().clear()
                    }
                    onDone()
                    dataStatus = doneMessage
                },
                onFailure = { dataStatus = "Couldn't reset: ${it.message}" },
            )
        }
    }

    fun saveGoals(context: Context) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.putGoals(
                    kcal = kcalGoal.toDoubleOrNull(),
                    proteinG = proteinGoal.toDoubleOrNull(),
                    dailySpend = spendGoal.toDoubleOrNull(),
                    workoutDaysPerWeek = workoutDaysGoal.toIntOrNull(),
                    bodyweightKg = bodyweightGoal.toDoubleOrNull(),
                )
            }
            goalsStatus = result.fold(
                onSuccess = { json ->
                    // PUT /goals returns {"updated": ..., "goals": {...}} — the flat "goals"
                    // object is what GET /goals returns and what applyGoalsJson expects.
                    val flatGoals = JSONObject(json).optJSONObject("goals")
                    if (flatGoals != null) {
                        BlakeDatabase.get(context).goalsDao().upsert(
                            GoalsCacheEntity(json = flatGoals.toString(), updatedAt = System.currentTimeMillis())
                        )
                    }
                    "Goals saved"
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToUnmatched: () -> Unit = {},
    onDataReset: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    var baseUrl by remember {
        mutableStateOf(prefs.getString(Prefs.KEY_BASE_URL, Prefs.DEFAULT_BASE_URL) ?: Prefs.DEFAULT_BASE_URL)
    }
    var apiKey by remember { mutableStateOf(prefs.getString(Prefs.KEY_API_KEY, "") ?: "") }
    var connectionStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadGoals(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xl, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        BlakeCard {
            SectionLabel("Connection")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Server URL") },
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton("Save connection", onClick = {
                prefs.edit()
                    .putString(Prefs.KEY_BASE_URL, baseUrl.trimEnd('/'))
                    .putString(Prefs.KEY_API_KEY, apiKey)
                    .apply()
                connectionStatus = "Saved"
            })
            if (connectionStatus.isNotBlank()) {
                Text(connectionStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.blake.success)
            }
        }

        BlakeCard {
            SectionLabel("Goals")
            if (vm.goalsLoading) {
                Text("Loading...", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(
                    value = vm.kcalGoal, onValueChange = { vm.kcalGoal = it },
                    label = { Text("Daily kcal target") },
                    shape = RoundedCornerShape(Radius.md), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.proteinGoal, onValueChange = { vm.proteinGoal = it },
                    label = { Text("Daily protein target (g)") },
                    shape = RoundedCornerShape(Radius.md), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.spendGoal, onValueChange = { vm.spendGoal = it },
                    label = { Text("Daily spend budget (₹)") },
                    shape = RoundedCornerShape(Radius.md), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.workoutDaysGoal, onValueChange = { vm.workoutDaysGoal = it },
                    label = { Text("Workout days per week") },
                    shape = RoundedCornerShape(Radius.md), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.bodyweightGoal, onValueChange = { vm.bodyweightGoal = it },
                    label = { Text("Bodyweight (kg), used to estimate kcal burned") },
                    shape = RoundedCornerShape(Radius.md), modifier = Modifier.fillMaxWidth()
                )
                PrimaryButton("Save goals", onClick = { vm.saveGoals(context) })
                if (vm.goalsStatus.isNotBlank()) {
                    Text(vm.goalsStatus, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        QuietButton("Unmatched items", onClick = onNavigateToUnmatched)

        DataCard(vm, context, onDataReset)
    }
}

@Composable
private fun DataCard(vm: SettingsViewModel, context: Context, onDataReset: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }

    BlakeCard {
        SectionLabel("Data")
        Text(
            "Resets undo log entries; nothing is deleted. Goals, your program, custom foods and recipes are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton("Reset today", onClick = { confirm = "day" })
        SecondaryButton("Reset all logs", onClick = { confirm = "all" })
        if (vm.dataStatus.isNotBlank()) {
            Text(vm.dataStatus, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val which = confirm
    if (which != null) {
        val isDay = which == "day"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (isDay) "Reset today?" else "Reset all logs?") },
            text = {
                Text(
                    if (isDay) "Today's meals, sets, spends and completed tasks will be undone."
                    else "Every meal, set, spend, task and chat entry will be undone. Goals, program, custom foods and recipes stay."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (isDay) vm.resetData(context, "/reset/day", "Today was reset", onDataReset)
                    else vm.resetData(context, "/reset/logs", "All logs were reset", onDataReset)
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
