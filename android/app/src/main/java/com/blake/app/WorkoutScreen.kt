package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.blake.app.ui.pop.PopButton
import com.blake.app.ui.pop.PopTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class MuscleGroupOption(val id: String, val label: String)

private val MUSCLE_GROUP_OPTIONS = listOf(
    MuscleGroupOption("chest", "Chest"),
    MuscleGroupOption("back", "Back"),
    MuscleGroupOption("legs", "Legs"),
    MuscleGroupOption("shoulders", "Shoulders"),
    MuscleGroupOption("biceps", "Biceps"),
    MuscleGroupOption("triceps", "Triceps"),
    MuscleGroupOption("core", "Core"),
    MuscleGroupOption("cardio", "Cardio"),
)

data class ExerciseItem(val group: String, val name: String, val sets: Int, val reps: String)

class WorkoutViewModel : ViewModel() {
    var location by mutableStateOf<String?>(null)
    var selectedGroups by mutableStateOf<Set<String>>(emptySet())
    var showSheet by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var exercises by mutableStateOf<List<ExerciseItem>>(emptyList())
    var errorText by mutableStateOf("")

    fun selectLocation(loc: String) {
        location = loc
        selectedGroups = emptySet()
        exercises = emptyList()
        errorText = ""
        showSheet = true
    }

    fun toggleGroup(group: String) {
        selectedGroups = if (group in selectedGroups) selectedGroups - group else selectedGroups + group
    }

    fun confirmGroups(context: Context) {
        val loc = location ?: return
        if (selectedGroups.isEmpty()) return
        showSheet = false
        loading = true
        errorText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.suggestWorkout(loc, selectedGroups.joinToString(","))
            }
            loading = false
            result.fold(
                onSuccess = { json -> exercises = parseExercises(json) },
                onFailure = { errorText = "Error: ${it.message}" }
            )
        }
    }
}

private fun parseExercises(json: String): List<ExerciseItem> {
    val groups = JSONObject(json).optJSONArray("groups") ?: return emptyList()
    val out = mutableListOf<ExerciseItem>()
    for (i in 0 until groups.length()) {
        val g = groups.getJSONObject(i)
        val groupName = g.optString("muscle_group")
        val exercisesArr = g.optJSONArray("exercises") ?: continue
        for (j in 0 until exercisesArr.length()) {
            val e = exercisesArr.getJSONObject(j)
            out.add(ExerciseItem(groupName, e.optString("exercise"), e.optInt("sets"), e.optString("reps")))
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(onStartSession: (List<ExerciseItem>) -> Unit, vm: WorkoutViewModel = viewModel()) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Where are you training?", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PopTile(
                selected = vm.location == "gym",
                onClick = { vm.selectLocation("gym") },
                modifier = Modifier.weight(1f),
            ) {
                LocationTileContent("🏢", "Gym")
            }
            PopTile(
                selected = vm.location == "home",
                onClick = { vm.selectLocation("home") },
                modifier = Modifier.weight(1f),
            ) {
                LocationTileContent("🏠", "Home")
            }
        }

        if (vm.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (vm.errorText.isNotBlank()) {
            Text(vm.errorText, color = MaterialTheme.colorScheme.error)
        }

        if (vm.exercises.isNotEmpty()) {
            val byGroup = vm.exercises.groupBy { it.group }
            byGroup.forEach { (group, items) ->
                val option = MUSCLE_GROUP_OPTIONS.find { it.id == group }
                Text(
                    "${MUSCLE_GROUP_EMOJI[group] ?: "🏋️"} ${option?.label ?: group}",
                    style = MaterialTheme.typography.titleMedium,
                )
                items.forEach { ex -> ExerciseCard(ex) }
            }

            PopButton(
                onClick = { onStartSession(vm.exercises) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Session", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (vm.showSheet) {
        MuscleGroupSheet(vm, onDismiss = { vm.showSheet = false }, onConfirm = { vm.confirmGroups(context) })
    }
}

@Composable
private fun LocationTileContent(emoji: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuscleGroupSheet(vm: WorkoutViewModel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            Text("What are you training today?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(260.dp),
            ) {
                items(MUSCLE_GROUP_OPTIONS) { option ->
                    MuscleGroupTile(
                        option = option,
                        selected = option.id in vm.selectedGroups,
                        onClick = { vm.toggleGroup(option.id) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PopButton(
                onClick = onConfirm,
                enabled = vm.selectedGroups.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Get My Plan (${vm.selectedGroups.size} selected)", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MuscleGroupTile(option: MuscleGroupOption, selected: Boolean, onClick: () -> Unit) {
    PopTile(selected = selected, onClick = onClick, height = 90.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(MUSCLE_GROUP_EMOJI[option.id] ?: "🏋️", style = MaterialTheme.typography.headlineMedium)
            Text(option.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ExerciseCard(ex: ExerciseItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val imageUrl = EXERCISE_IMAGES[ex.name]
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = ex.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(MUSCLE_GROUP_EMOJI[ex.group] ?: "🏋️", style = MaterialTheme.typography.headlineSmall)
            }
            Text(ex.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(
                if (ex.sets > 1) "${ex.sets} × ${ex.reps}" else ex.reps,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
