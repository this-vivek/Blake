package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
fun SettingsScreen(onBack: () -> Unit, onNavigateToUnmatched: () -> Unit = {}, vm: SettingsViewModel = viewModel()) {
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
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text("Connection", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                prefs.edit()
                    .putString(Prefs.KEY_BASE_URL, baseUrl.trimEnd('/'))
                    .putString(Prefs.KEY_API_KEY, apiKey)
                    .apply()
                connectionStatus = "Saved"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save connection") }
        if (connectionStatus.isNotBlank()) Text(connectionStatus)

        Divider()

        Text("Goals", style = MaterialTheme.typography.titleMedium)
        if (vm.goalsLoading) {
            Text("Loading...")
        } else {
            OutlinedTextField(
                value = vm.kcalGoal, onValueChange = { vm.kcalGoal = it },
                label = { Text("Daily kcal target") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.proteinGoal, onValueChange = { vm.proteinGoal = it },
                label = { Text("Daily protein target (g)") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.spendGoal, onValueChange = { vm.spendGoal = it },
                label = { Text("Daily spend budget (₹)") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.workoutDaysGoal, onValueChange = { vm.workoutDaysGoal = it },
                label = { Text("Workout days per week") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.bodyweightGoal, onValueChange = { vm.bodyweightGoal = it },
                label = { Text("Bodyweight (kg) — used to estimate kcal burned") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.saveGoals(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save goals")
            }
            if (vm.goalsStatus.isNotBlank()) Text(vm.goalsStatus)
        }

        Divider()

        TextButton(onClick = onNavigateToUnmatched, modifier = Modifier.fillMaxWidth()) {
            Text("Unmatched items")
        }
    }
}
