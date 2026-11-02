package com.blake.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.CachedEventEntity
import com.blake.app.data.OutboxRepository
import com.blake.app.ui.theme.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private fun defaultMealForHour(hour: Int): String = when (hour) {
    in 5..10 -> "breakfast"
    in 11..15 -> "lunch"
    in 16..18 -> "snack"
    in 19..22 -> "dinner"
    else -> "snack"
}

class TodayViewModel : ViewModel() {
    var mealType by mutableStateOf(
        defaultMealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    )
    var mealLoading by mutableStateOf(false)
    var mealTotalText by mutableStateOf("")
    var mealNames by mutableStateOf<List<String>>(emptyList())
    var mealGrams by mutableStateOf<List<String>>(emptyList())
    var logResultText by mutableStateOf("")

    var mealRefreshing by mutableStateOf(false)

    fun suggestMeal(context: Context) {
        mealLoading = true
        logResultText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestMeal(mealType) }
            mealLoading = false
            applyMealResult(result)
        }
    }

    /** LLM-backed refresh for variety — see app/meal_refresh.py. Falls back to the plain
     * deterministic suggestion server-side if the LLM call fails, so this never errors out. */
    fun refreshMeal(context: Context) {
        mealRefreshing = true
        logResultText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestMealRefresh(mealType) }
            mealRefreshing = false
            applyMealResult(result)
        }
    }

    private fun applyMealResult(result: Result<String>) {
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

    fun updateGram(index: Int, value: String) {
        mealGrams = mealGrams.toMutableList().also { it[index] = value }
    }

    /** Queues the write to the outbox instead of waiting on the network — logging an already-
     * known suggestion works instantly offline, and syncs exactly once when reconnected
     * (WorkManager's network constraint + the backend's client_id idempotency). */
    fun logMeal(context: Context) {
        val items = mealNames.indices.mapNotNull { i ->
            val grams = mealGrams[i].toDoubleOrNull()
            if (grams != null && grams > 0) mealNames[i] to grams else null
        }
        if (items.isEmpty()) return
        viewModelScope.launch {
            val itemsJson = JSONArray()
            items.forEach { (name, grams) ->
                itemsJson.put(JSONObject().put("name", name).put("grams", grams))
            }
            val bodyJson = JSONObject().put("meal", mealType).put("items", itemsJson).toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/meal", "POST", bodyJson, "meal")

            // Optimistic row: shows what was submitted, not fabricated macros — real numbers
            // land once SyncWorker reconciles this with the server's response.
            val payload = JSONObject().put("meal", mealType).put("items", itemsJson)
            BlakeDatabase.get(context).eventDao().upsert(
                CachedEventEntity(
                    id = -outboxId,
                    module = "meals",
                    kind = "meal",
                    ts = System.currentTimeMillis() / 1000.0,
                    payloadJson = payload.toString(),
                    pending = true,
                )
            )
            logResultText = "Logged for $mealType — syncing..."
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${vm.mealType.replaceFirstChar { it.uppercase() }} suggestion",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = { vm.refreshMeal(context) }, enabled = !vm.mealRefreshing) {
                Text(if (vm.mealRefreshing) "Refreshing..." else "🔄 Refresh")
            }
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
        if (vm.chatResultText.isNotBlank()) {
            ChatResultCard(vm)
        }
        Composer(vm)
    }
}

@Composable
private fun Composer(vm: TodayViewModel) {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> vm.chatImageUri = uri }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.chatImageUri?.let { uri ->
                Box(contentAlignment = Alignment.TopEnd) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    IconButton(
                        onClick = { vm.chatImageUri = null },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                            Text(
                                "✕",
                                modifier = Modifier.padding(4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.chatInput,
                    onValueChange = { vm.chatInput = it },
                    placeholder = { Text("Log anything...") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Text("📷")
                }
                IconButton(
                    onClick = {
                        val uri = vm.chatImageUri
                        val bytes = uri?.let {
                            context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                        }
                        val mimeType = uri?.let { context.contentResolver.getType(it) }
                        vm.sendChat(context, bytes, mimeType)
                    },
                    enabled = !vm.chatLoading && (vm.chatInput.isNotBlank() || vm.chatImageUri != null),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (!vm.chatLoading && (vm.chatInput.isNotBlank() || vm.chatImageUri != null)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                ) {
                    if (vm.chatLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("➤", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
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
