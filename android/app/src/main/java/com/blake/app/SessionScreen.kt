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
import com.blake.app.ui.pop.PopButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hands off the confirmed plan from WorkoutScreen to SessionScreen without needing to
 * serialize it through Navigation Compose's string-based route args. */
object WorkoutSessionStore {
    var pendingExercises: List<ExerciseItem> = emptyList()
}

class SessionViewModel : ViewModel() {
    var exercises: List<ExerciseItem> = emptyList()
    var currentIndex by mutableStateOf(0)
    var setsLoggedForCurrent by mutableStateOf(0)
    var weightInput by mutableStateOf("")
    var sessionKcal by mutableStateOf(0.0)
    var logging by mutableStateOf(false)
    var statusText by mutableStateOf("")
    var finished by mutableStateOf(false)
    private var initialized = false

    fun init(exercises: List<ExerciseItem>) {
        if (initialized) return
        initialized = true
        this.exercises = exercises
    }

    val currentExercise: ExerciseItem? get() = exercises.getOrNull(currentIndex)

    private fun targetReps(reps: String): Int = reps.filter { it.isDigit() }.toIntOrNull() ?: 1

    fun logSet(context: Context) {
        val ex = currentExercise ?: return
        val weight = weightInput.toDoubleOrNull()
        if (weight == null || weight < 0) {
            statusText = "Enter a valid weight (0 is fine for bodyweight moves)"
            return
        }
        logging = true
        statusText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.logSet(ex.name, weight, targetReps(ex.reps)) }
            logging = false
            result.fold(
                onSuccess = { json ->
                    val obj = JSONObject(json)
                    setsLoggedForCurrent++
                    sessionKcal += obj.optDouble("kcal_burned", 0.0)
                    val prText = if (obj.optBoolean("is_pr")) " 🎉 New PR!" else ""
                    statusText = "Set $setsLoggedForCurrent logged.$prText"
                },
                onFailure = { statusText = "Error: ${it.message}" }
            )
        }
    }

    fun nextExercise() {
        if (currentIndex < exercises.lastIndex) {
            currentIndex++
            setsLoggedForCurrent = 0
            statusText = ""
        } else {
            finished = true
        }
    }
}

@Composable
fun SessionScreen(onFinish: () -> Unit, vm: SessionViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.init(WorkoutSessionStore.pendingExercises) }

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

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.weightInput,
                        onValueChange = { vm.weightInput = it },
                        label = { Text("Weight (kg)") },
                        modifier = Modifier.weight(1f),
                    )
                    PopButton(onClick = { vm.logSet(context) }, enabled = !vm.logging) {
                        Text(
                            if (vm.logging) "..." else "Log Set (${vm.setsLoggedForCurrent}/${ex.sets})",
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

@Composable
private fun SessionSummary(vm: SessionViewModel, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎉", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Session complete", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("${vm.sessionKcal.toInt()} kcal burned this session (approx.)", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        PopButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done", fontWeight = FontWeight.Bold)
        }
    }
}
