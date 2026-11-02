package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.CachedEventEntity
import com.blake.app.data.OutboxRepository
import com.blake.app.ui.pop.PopButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DEFAULT_REST_SECONDS = 90

/** Hands off the confirmed plan from WorkoutScreen to SessionScreen without needing to
 * serialize it through Navigation Compose's string-based route args. */
object WorkoutSessionStore {
    var pendingExercises: List<ExerciseItem> = emptyList()
}

private data class LoggedSet(val exercise: String, val group: String, val weightKg: Double, val reps: Int)

class SessionViewModel : ViewModel() {
    var exercises: List<ExerciseItem> = emptyList()
    var currentIndex by mutableStateOf(0)
    var setsLoggedForCurrent by mutableStateOf(0)
    var weightInput by mutableStateOf("")
    var suggestionText by mutableStateOf("")
    var sessionKcal by mutableStateOf(0.0)
    var statusText by mutableStateOf("")
    var finished by mutableStateOf(false)
    var restSecondsRemaining by mutableStateOf(0)
    private var restJob: Job? = null
    private var initialized = false

    private var sessionStartMs = 0L
    var sessionEndMs by mutableStateOf<Long?>(null)
    private val loggedSets = mutableListOf<LoggedSet>()
    // PRs are only known once this session's sets reach the server (Epley is calculated
    // there, never duplicated client-side) — populated by checkForPrs after sync.
    var prsAfterSync by mutableStateOf<List<String>>(emptyList())

    val volumeByGroup: Map<String, Double>
        get() = loggedSets.groupBy { it.group }
            .mapValues { (_, sets) -> sets.sumOf { it.weightKg * it.reps } }

    val durationMs: Long
        get() = (sessionEndMs ?: System.currentTimeMillis()) - sessionStartMs

    fun init(exercises: List<ExerciseItem>) {
        if (initialized) return
        initialized = true
        this.exercises = exercises
        sessionStartMs = System.currentTimeMillis()
    }

    val currentExercise: ExerciseItem? get() = exercises.getOrNull(currentIndex)

    /** Prefills the weight input from the server's deterministic progressive-overload
     * suggestion (app/today.py's workouts.suggested_next, P4.1) — the model never guesses
     * this, it's the same weight-last-session + increment logic, just surfaced here. */
    fun loadSuggestion(context: Context) {
        val ex = currentExercise ?: return
        suggestionText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestNextSet(ex.name) }
            result.onSuccess { json ->
                val obj = JSONObject(json)
                if (!obj.has("suggested_weight_kg") || obj.isNull("suggested_weight_kg")) return@onSuccess
                val suggested = obj.optDouble("suggested_weight_kg")
                val lastWeight = obj.optDouble("last_weight_kg")
                val lastReps = obj.optInt("last_reps")
                weightInput = if (suggested == suggested.toInt().toDouble()) {
                    suggested.toInt().toString()
                } else {
                    suggested.toString()
                }
                suggestionText = "Last: ${lastWeight.toInt()}kg × $lastReps — try ${weightInput}kg"
            }
        }
    }

    private fun startRestTimer() {
        restJob?.cancel()
        restSecondsRemaining = DEFAULT_REST_SECONDS
        restJob = viewModelScope.launch {
            while (restSecondsRemaining > 0) {
                delay(1000)
                restSecondsRemaining--
            }
        }
    }

    private fun targetReps(reps: String): Int = reps.filter { it.isDigit() }.toIntOrNull() ?: 1

    /** Queues the write and updates local state immediately — this never waits on the
     * network, so logging a set feels instant even offline. The kcal shown is a client-side
     * *estimate* (see KcalEstimate.kt) purely for that instant feedback; the server's own
     * computed value (summed in /today's kcal_burned_today) is the real number, reconciled
     * once SyncWorker actually delivers this set. */
    fun logSet(context: Context) {
        val ex = currentExercise ?: return
        val weight = weightInput.toDoubleOrNull()
        if (weight == null || weight < 0) {
            statusText = "Enter a valid weight (0 is fine for bodyweight moves)"
            return
        }
        val reps = targetReps(ex.reps)
        val estimatedKcal = estimateKcalForSet(ex.group)

        setsLoggedForCurrent++
        sessionKcal += estimatedKcal
        loggedSets.add(LoggedSet(ex.name, ex.group, weight, reps))
        statusText = "Set $setsLoggedForCurrent logged (syncing...)"
        startRestTimer()

        viewModelScope.launch {
            val bodyJson = JSONObject()
                .put("exercise", ex.name)
                .put("weight_kg", weight)
                .put("reps", reps)
                .toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/set", "POST", bodyJson, "set")

            val payload = JSONObject()
                .put("exercise", ex.name.lowercase())
                .put("weight_kg", weight)
                .put("reps", reps)
                .put("kcal_burned", estimatedKcal)
            BlakeDatabase.get(context).eventDao().upsert(
                CachedEventEntity(
                    id = -outboxId,
                    module = "workouts",
                    kind = "set",
                    ts = System.currentTimeMillis() / 1000.0,
                    payloadJson = payload.toString(),
                    pending = true,
                )
            )
        }
    }

    fun nextExercise() {
        restJob?.cancel()
        restSecondsRemaining = 0
        if (currentIndex < exercises.lastIndex) {
            currentIndex++
            setsLoggedForCurrent = 0
            statusText = ""
            weightInput = ""
            suggestionText = ""
        } else {
            finished = true
            sessionEndMs = System.currentTimeMillis()
        }
    }

    /** Checks /today's server-computed PRs (Epley is only ever calculated there, see
     * log_set) for any exercise logged this session. Called more than once from the summary
     * screen since a set may still be mid-sync the first time this runs. */
    fun checkForPrs(context: Context) {
        val exerciseNames = loggedSets.map { it.exercise.lowercase() }.toSet()
        if (exerciseNames.isEmpty()) return
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.today() }
            result.onSuccess { json ->
                val prsArr = JSONObject(json).optJSONObject("training")?.optJSONArray("prs_today")
                    ?: return@onSuccess
                val names = mutableListOf<String>()
                for (i in 0 until prsArr.length()) {
                    val name = prsArr.getJSONObject(i).optString("exercise")
                    if (name in exerciseNames) names.add(name)
                }
                prsAfterSync = names
            }
        }
    }
}

@Composable
fun SessionScreen(onFinish: () -> Unit, vm: SessionViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.init(WorkoutSessionStore.pendingExercises) }
    LaunchedEffect(vm.currentIndex) { vm.loadSuggestion(context) }

    if (vm.finished) {
        SessionSummary(vm, onDone = onFinish)
        return
    }

    val ex = vm.currentExercise
    if (ex == null) {
        onFinish()
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val imageUrl = EXERCISE_IMAGES[ex.name]
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = ex.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
            } else {
                Text(MUSCLE_GROUP_EMOJI[ex.group] ?: "🏋️", style = MaterialTheme.typography.displayLarge)
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Exercise ${vm.currentIndex + 1} of ${vm.exercises.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(ex.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Target: ${if (ex.sets > 1) "${ex.sets} sets × ${ex.reps}" else ex.reps}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Burned this session: ${vm.sessionKcal.toInt()} kcal (approx.)",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (vm.suggestionText.isNotBlank()) {
                    Text(vm.suggestionText, style = MaterialTheme.typography.bodySmall)
                }
                if (vm.restSecondsRemaining > 0) {
                    Text(
                        "Rest: ${vm.restSecondsRemaining}s",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.weightInput,
                        onValueChange = { vm.weightInput = it },
                        label = { Text("Weight (kg)") },
                        modifier = Modifier.weight(1f),
                    )
                    PopButton(onClick = { vm.logSet(context) }) {
                        Text(
                            "Log Set (${vm.setsLoggedForCurrent}/${ex.sets})",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (vm.statusText.isNotBlank()) {
                    Text(vm.statusText, style = MaterialTheme.typography.bodySmall)
                }

                PopButton(onClick = { vm.nextExercise() }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (vm.currentIndex < vm.exercises.lastIndex) "Next Exercise →" else "Finish Session",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun SessionSummary(vm: SessionViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    // Checked twice: once immediately (covers an already-synced earlier set) and once after
    // a few seconds (covers a set still mid-sync when this screen first appeared).
    LaunchedEffect(Unit) {
        vm.checkForPrs(context)
        delay(4000)
        vm.checkForPrs(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎉", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Session complete", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(formatDuration(vm.durationMs), style = MaterialTheme.typography.bodyLarge)
        Text("${vm.sessionKcal.toInt()} kcal burned (approx.)", style = MaterialTheme.typography.bodyLarge)

        if (vm.volumeByGroup.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Volume (weight × reps)", style = MaterialTheme.typography.titleSmall)
            vm.volumeByGroup.toSortedMap().forEach { (group, volume) ->
                Text("${group.replaceFirstChar { it.uppercase() }}: ${volume.toInt()} kg", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (vm.prsAfterSync.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            vm.prsAfterSync.forEach { exercise ->
                Text("🏆 PR: ${exercise.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(24.dp))
        PopButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done", fontWeight = FontWeight.Bold)
        }
    }
}
