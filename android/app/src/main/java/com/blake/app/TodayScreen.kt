package com.blake.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.blake.app.ui.theme.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

private val MUSCLE_GROUPS = listOf(
    "chest", "back", "legs", "shoulders", "biceps", "triceps", "core", "cardio"
)
private val MEAL_TYPES = listOf("breakfast", "lunch", "dinner", "snack")

private fun defaultMealForHour(hour: Int): String = when (hour) {
    in 5..10 -> "breakfast"
    in 11..15 -> "lunch"
    in 16..18 -> "snack"
    in 19..22 -> "dinner"
    else -> "snack"
}

class TodayViewModel : ViewModel() {
    var location by mutableStateOf<String?>(null)
    var selectedGroups by mutableStateOf<Set<String>>(emptySet())
    var workoutText by mutableStateOf<List<String>>(emptyList())
    var workoutLoading by mutableStateOf(false)

    var mealType by mutableStateOf(
        defaultMealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    )
    var mealLoading by mutableStateOf(false)
    var mealTotalText by mutableStateOf("")
    var mealNames by mutableStateOf<List<String>>(emptyList())
    var mealGrams by mutableStateOf<List<String>>(emptyList())
    var logResultText by mutableStateOf("")

    fun toggleGroup(context: Context, group: String) {
        selectedGroups = if (group in selectedGroups) selectedGroups - group
        else selectedGroups + group
        if (selectedGroups.isNotEmpty() && location != null) fetchWorkout(context)
    }

    fun setLocation(context: Context, loc: String) {
        location = loc
        if (selectedGroups.isNotEmpty()) fetchWorkout(context)
    }

    private fun fetchWorkout(context: Context) {
        val loc = location ?: return
        workoutLoading = true
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.suggestWorkout(loc, selectedGroups.joinToString(","))
            }
            workoutLoading = false
            workoutText = result.fold(
                onSuccess = { json -> formatWorkout(json) },
                onFailure = { listOf("Error: ${it.message}") }
            )
        }
    }

    fun suggestMeal(context: Context) {
        mealLoading = true
        logResultText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestMeal(mealType) }
            mealLoading = false
            result.onSuccess { json ->
                val obj = JSONObject(json)
                val itemsArr = obj.optJSONArray("items")
                val names = mutableListOf<String>()
                val grams = mutableListOf<String>()
                if (itemsArr != null) {
                    for (i in 0 until itemsArr.length()) {
                        val it = itemsArr.getJSONObject(i)
                        names.add(it.optString("name"))
                        grams.add(it.optInt("grams").toString())
                    }
                }
                mealNames = names
                mealGrams = grams
                val total = obj.optJSONObject("total")
                mealTotalText = if (total != null) {
                    "Approx: ${Format.kcal(total.optDouble("kcal", 0.0))}, " +
                        "${Format.grams(total.optDouble("protein", 0.0))} protein"
                } else ""
            }
            result.onFailure {
                mealNames = emptyList()
                mealTotalText = "Error: ${it.message}"
            }
        }
    }

    fun updateGram(index: Int, value: String) {
        mealGrams = mealGrams.toMutableList().also { it[index] = value }
    }

    fun logMeal(context: Context) {
        val items = mealNames.indices.mapNotNull { i ->
            val grams = mealGrams[i].toDoubleOrNull()
            if (grams != null && grams > 0) mealNames[i] to grams else null
        }
        if (items.isEmpty()) return
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.logMeal(mealType, items) }
            logResultText = result.fold(
                onSuccess = { json ->
                    val total = JSONObject(json).optJSONObject("total")
                    if (total != null) "Logged! Total: ${Format.kcal(total.optDouble("kcal", 0.0))}"
                    else "Logged, but response was unexpected: $json"
                },
                onFailure = { "Error: ${it.message}" }
            )
        }
    }

    // Free-text/photo logging, docked on Today — replaces the old standalone Log tab.
    var chatInput by mutableStateOf("")
    var chatImageUri by mutableStateOf<Uri?>(null)
    var chatLoading by mutableStateOf(false)
    var chatResultText by mutableStateOf("")
    var chatEventIds by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    var chatUndone by mutableStateOf<Set<Int>>(emptySet())

    fun sendChat(context: Context, imageBytes: ByteArray?, imageMimeType: String?) {
        if (chatInput.isBlank() && imageBytes == null) return
        chatLoading = true
        chatResultText = ""
        chatEventIds = emptyList()
        chatUndone = emptySet()
        val api = Prefs.apiFor(context)
        val text = chatInput
        lastSentText = text
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.chat(text, imageBytes, imageMimeType ?: "image/jpeg")
            }
            chatLoading = false
            result.fold(
                onSuccess = { json ->
                    val (summary, ids) = parseChatResult(json)
                    chatResultText = summary
                    chatEventIds = ids
                    chatInput = ""
                    chatImageUri = null
                },
                onFailure = { chatResultText = "Error: ${it.message}" }
            )
        }
    }

    var lastSentText by mutableStateOf("")

    fun undoChatEvent(context: Context, eventId: Int) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.voidEvent(eventId) }
            result.onSuccess { chatUndone = chatUndone + eventId }
            result.onFailure { chatResultText = "Couldn't undo: ${it.message}" }
        }
    }

    /** Void every event from the last reply, then put the original text back for editing. */
    fun editChatResult(context: Context) {
        val ids = chatEventIds.map { it.second }.filter { it !in chatUndone }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            ids.forEach { id ->
                val result = withContext(Dispatchers.IO) { api.voidEvent(id) }
                result.onSuccess { chatUndone = chatUndone + id }
            }
            chatInput = lastSentText
            chatResultText = ""
            chatEventIds = emptyList()
        }
    }
}

private fun parseChatResult(json: String): Pair<String, List<Pair<String, Int>>> {
    val obj = JSONObject(json)
    val text = obj.optString("text")
    val calls = obj.optJSONArray("calls")
    val ids = mutableListOf<Pair<String, Int>>()
    if (calls != null) {
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val output = call.optJSONObject("output") ?: continue
            if (output.has("event_id")) {
                ids.add(call.optString("name") to output.optInt("event_id"))
            }
        }
    }
    return text to ids
}

private fun formatWorkout(json: String): List<String> {
    val groups = JSONObject(json).optJSONArray("groups") ?: return listOf("No routine found")
    val out = mutableListOf<String>()
    for (i in 0 until groups.length()) {
        val g = groups.getJSONObject(i)
        out.add("— ${g.optString("muscle_group").uppercase()} —")
        val exercises = g.optJSONArray("exercises") ?: continue
        for (j in 0 until exercises.length()) {
            val e = exercises.getJSONObject(j)
            out.add("${e.optString("exercise")}: ${e.optInt("sets")} x ${e.optString("reps")}")
        }
    }
    return if (out.isEmpty()) listOf("No routine found") else out
}

@Composable
fun TodayScreen(vm: TodayViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (vm.mealNames.isEmpty()) vm.suggestMeal(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Workout", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.location == "gym",
                onClick = { vm.setLocation(context, "gym") },
                label = { Text("Gym") }
            )
            FilterChip(
                selected = vm.location == "home",
                onClick = { vm.setLocation(context, "home") },
                label = { Text("Home") }
            )
        }
        if (vm.location != null) {
            FlowRowChips(MUSCLE_GROUPS, vm.selectedGroups) { vm.toggleGroup(context, it) }
        }
        if (vm.workoutLoading) {
            Text("Loading...")
        } else {
            vm.workoutText.forEach { line -> Text(line) }
        }

        Divider()

        Text("Food suggestion", style = MaterialTheme.typography.titleMedium)
        FlowRowChips(MEAL_TYPES, setOf(vm.mealType)) {
            vm.mealType = it
            vm.suggestMeal(context)
        }

        if (vm.mealLoading) {
            Text("Loading...")
        } else {
            vm.mealNames.forEachIndexed { idx, name ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(name, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = vm.mealGrams.getOrElse(idx) { "" },
                        onValueChange = { vm.updateGram(idx, it) },
                        label = { Text("g") },
                        modifier = Modifier.width(90.dp)
                    )
                }
            }
            if (vm.mealTotalText.isNotBlank()) {
                Text(vm.mealTotalText, style = MaterialTheme.typography.bodyMedium)
            }
            if (vm.mealNames.isNotEmpty()) {
                Button(onClick = { vm.logMeal(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Log this meal")
                }
            }
            if (vm.logResultText.isNotBlank()) {
                Text(vm.logResultText)
            }
        }

        Divider()

        Text("Log anything", style = MaterialTheme.typography.titleMedium)
        ChatInputBar(vm)
        if (vm.chatResultText.isNotBlank()) {
            ChatResultCard(vm)
        }
    }
}

@Composable
private fun ChatInputBar(vm: TodayViewModel) {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> vm.chatImageUri = uri }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.chatInput,
            onValueChange = { vm.chatInput = it },
            placeholder = { Text("Log anything...") },
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { pickImage.launch("image/*") }) {
            Text(if (vm.chatImageUri == null) "📷" else "✓")
        }
        Button(
            onClick = {
                val uri = vm.chatImageUri
                val bytes = uri?.let { context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }
                val mimeType = uri?.let { context.contentResolver.getType(it) }
                vm.sendChat(context, bytes, mimeType)
            },
            enabled = !vm.chatLoading && (vm.chatInput.isNotBlank() || vm.chatImageUri != null),
        ) {
            Text(if (vm.chatLoading) "..." else "Send")
        }
    }
}

@Composable
private fun ChatResultCard(vm: TodayViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.chatResultText)
            if (vm.chatEventIds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val allUndone = vm.chatEventIds.all { it.second in vm.chatUndone }
                    if (allUndone) {
                        Text("Undone", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TextButton(onClick = { vm.editChatResult(context) }) { Text("Edit") }
                        vm.chatEventIds.forEach { (_, eventId) ->
                            if (eventId !in vm.chatUndone) {
                                TextButton(onClick = { vm.undoChatEvent(context, eventId) }) {
                                    Text("Undo")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowRowChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    FilterChip(
                        selected = option in selected,
                        onClick = { onToggle(option) },
                        label = { Text(option) }
                    )
                }
            }
        }
    }
}
