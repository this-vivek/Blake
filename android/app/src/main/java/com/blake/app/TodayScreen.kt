package com.blake.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
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
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.CachedEventEntity
import com.blake.app.data.OutboxRepository
import com.blake.app.data.TodayCacheEntity
import com.blake.app.ui.theme.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

internal val MEAL_SLOTS = listOf("breakfast", "lunch", "dinner", "snack")
internal val SLOT_LABELS = mapOf("breakfast" to "B", "lunch" to "L", "dinner" to "D", "snack" to "S")

internal fun defaultMealForHour(hour: Int): String = when (hour) {
    in 5..10 -> "breakfast"
    in 11..15 -> "lunch"
    in 16..18 -> "snack"
    in 19..22 -> "dinner"
    else -> "snack"
}

class TodayViewModel : ViewModel() {
    var todayJson by mutableStateOf<JSONObject?>(null)
    // ✓ shown for a slot the moment it's logged, before the server round-trip confirms it —
    // /today's own meal_slots_logged won't reflect a pending outbox write until it syncs.
    var locallyLoggedSlots by mutableStateOf<Set<String>>(emptySet())
    var editingSlot by mutableStateOf<String?>(null)

    var mealType by mutableStateOf(
        defaultMealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    )
    var mealLoading by mutableStateOf(false)
    var mealTotalText by mutableStateOf("")
    var mealNames by mutableStateOf<List<String>>(emptyList())
    var mealGrams by mutableStateOf<List<String>>(emptyList())
    var logResultText by mutableStateOf("")

    var mealRefreshing by mutableStateOf(false)

    var expenseAmount by mutableStateOf("")
    var expenseResultText by mutableStateOf("")
    // Added to /today's own spend.actual for display, since a queued expense doesn't land
    // server-side until SyncWorker drains it — a lighter optimistic update than meal/set's
    // full outbox+cached_events row, scoped to just this card's running total.
    var pendingSpend by mutableStateOf(0.0)

    var completingTaskIds by mutableStateOf<Set<Int>>(emptySet())

    // Training card: today's program day, where you're training, and starting the session.
    var trainingLocation by mutableStateOf("gym")
    var programMuscleGroup by mutableStateOf<String?>(null)
    var programIsRest by mutableStateOf(false)
    var programLoaded by mutableStateOf(false)
    var startingSession by mutableStateOf(false)
    var startError by mutableStateOf("")

    fun loadProgram(context: Context) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.programToday() }
            result.onSuccess { json ->
                val day = JSONObject(json).optJSONObject("day")
                programIsRest = day?.optString("type") == "rest"
                programMuscleGroup = if (!programIsRest) day?.optString("muscle_group") else null
                programLoaded = true
            }
        }
    }

    /** Loads today's exercises for the program day, then hands them to the session screen.
     * Any failure is shown on the card; the button never fails silently. */
    fun startSession(context: Context, onReady: () -> Unit) {
        val group = programMuscleGroup
        if (group.isNullOrBlank()) {
            startError = "No training day for today. Tell Blake your program, e.g. \"set my program: chest, back, legs, rest\"."
            return
        }
        startingSession = true
        startError = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestWorkout(trainingLocation, group) }
            startingSession = false
            result.fold(
                onSuccess = { json ->
                    val exercises = parseExercises(json)
                    if (exercises.isEmpty()) {
                        startError = "No exercises found for $group at the $trainingLocation."
                    } else {
                        WorkoutSessionStore.pendingExercises = exercises
                        WorkoutSessionStore.isProgramSession = true
                        onReady()
                    }
                },
                onFailure = { startError = "Couldn't load the session: ${it.message}" },
            )
        }
    }

    /** Cache-first: render the last-known /today payload from Room immediately (if any),
     * then refresh from the network and persist the fresh copy for next time. */
    fun loadToday(context: Context) {
        viewModelScope.launch {
            val cached = BlakeDatabase.get(context).todayDao().observe().first()
            if (cached != null) todayJson = JSONObject(cached.json)
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.today() }
            result.onSuccess { json ->
                todayJson = JSONObject(json)
                BlakeDatabase.get(context).todayDao().upsert(
                    TodayCacheEntity(json = json, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun startEditingSlot(context: Context, slot: String) {
        editingSlot = slot
        mealType = slot
        mealNames = emptyList()
        mealTotalText = ""
        suggestMeal(context)
    }

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
        val slot = mealType
        locallyLoggedSlots = locallyLoggedSlots + slot
        viewModelScope.launch {
            val itemsJson = JSONArray()
            items.forEach { (name, grams) ->
                itemsJson.put(JSONObject().put("name", name).put("grams", grams))
            }
            val bodyJson = JSONObject().put("meal", slot).put("items", itemsJson).toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/meal", "POST", bodyJson, "meal")

            // Optimistic row: shows what was submitted, not fabricated macros — real numbers
            // land once SyncWorker reconciles this with the server's response.
            val payload = JSONObject().put("meal", slot).put("items", itemsJson)
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
            logResultText = "Logged for $slot — syncing..."
            editingSlot = null
        }
    }

    /** One-tap log of a slot's established "usual" combo (app/today.py's usual_combo) —
     * skips the suggestion editor entirely, using the same outbox/optimistic-row pattern as
     * logMeal so it works offline too. */
    fun logUsual(context: Context, slot: String, usual: JSONObject) {
        val items = usual.optJSONArray("items") ?: return
        locallyLoggedSlots = locallyLoggedSlots + slot
        viewModelScope.launch {
            val bodyJson = JSONObject().put("meal", slot).put("items", items).toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/meal", "POST", bodyJson, "meal")
            val payload = JSONObject().put("meal", slot).put("items", items)
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
            logResultText = "Logged usual $slot — syncing..."
        }
    }

    fun logExpense(context: Context, amount: Double, category: String, note: String) {
        if (amount <= 0) return
        pendingSpend += amount
        expenseResultText = "Logged ₹${amount.toInt()}, syncing..."
        viewModelScope.launch {
            // Where it was spent, when allowed; never blocks or fails the save.
            val location = LocationHelper.current(context)
            val body = JSONObject()
                .put("amount", amount)
                .put("category", category)
                .put("note", note.trim())
            if (location != null) {
                body.put("lat", location.latitude)
                    .put("lon", location.longitude)
                    .put("accuracy_m", location.accuracy.toDouble())
            }
            OutboxRepository.enqueue(context, "/log/expense", "POST", body.toString(), "expense")
        }
    }

    /** Optimistically hides the task immediately; /tasks/{id}/complete is already safe to
     * retry (completing an already-done task just 404s, never double-completes), so this
     * doesn't need the outbox — a direct call matches how Undo/void already work elsewhere. */
    fun completeTask(context: Context, taskId: Int) {
        completingTaskIds = completingTaskIds + taskId
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.completeTask(taskId) }
            result.onFailure { completingTaskIds = completingTaskIds - taskId }
        }
    }

    // Free-text/photo logging, docked on Today — replaces the old standalone Log tab.
    var chatInput by mutableStateOf("")
    var chatImageUri by mutableStateOf<Uri?>(null)
    var chatLoading by mutableStateOf(false)
    var chatResultText by mutableStateOf("")
    var chatEventIds by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    var chatUndone by mutableStateOf<Set<Int>>(emptySet())
    /** Logged meal from the last reply: event id + (food name, editable grams text). */
    var chatMealId by mutableStateOf<Int?>(null)
    var chatMealItems by mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** Tries the network directly first, for the normal case — this keeps chat's rich
     * immediate result (summary text, per-entry Undo) rather than always deferring to the
     * outbox. Only on failure (most commonly: offline) does it fall back to queueing, since
     * an image can't travel through the outbox's instant-local-row pattern the way meal/set
     * logging does — there's no structured payload to show until the server actually parses it. */
    fun sendChat(context: Context, imageBytes: ByteArray?, imageMimeType: String?) {
        if (chatInput.isBlank() && imageBytes == null) return
        chatLoading = true
        chatResultText = ""
        chatEventIds = emptyList()
        chatUndone = emptySet()
        chatMealId = null
        chatMealItems = emptyList()
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
                    parseChatMeal(json)?.let { (id, items) ->
                        chatMealId = id
                        chatMealItems = items
                    }
                    chatInput = ""
                    chatImageUri = null
                },
                onFailure = {
                    viewModelScope.launch(Dispatchers.IO) {
                        OutboxRepository.enqueueChat(context, text, imageBytes, imageMimeType)
                    }
                    chatResultText = "Offline — queued, will parse once reconnected"
                    chatInput = ""
                    chatImageUri = null
                }
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

    fun setChatMealGrams(index: Int, grams: String) {
        chatMealItems = chatMealItems.mapIndexed { i, it -> if (i == index) it.first to grams else it }
    }

    /** Server logs the corrected meal and voids the original in one call. */
    fun saveChatMealEdit(context: Context) {
        val id = chatMealId ?: return
        val items = org.json.JSONArray()
        chatMealItems.forEach { (name, g) ->
            val grams = g.toDoubleOrNull() ?: return
            if (grams <= 0) return
            items.put(JSONObject().put("name", name).put("grams", grams))
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.rawRequest("POST", "/meals/$id/edit", JSONObject().put("items", items).toString())
            }
            result.fold(
                onSuccess = { body ->
                    val obj = JSONObject(body)
                    if (obj.has("error")) {
                        chatResultText = "Couldn't save: ${obj.optString("error")}"
                    } else {
                        val newId = obj.optInt("event_id")
                        chatEventIds = chatEventIds.map { if (it.second == id) it.first to newId else it }
                        chatMealId = newId
                        val total = obj.optJSONObject("total")
                        chatResultText = "Updated — ${Format.kcal(total?.optDouble("kcal", 0.0) ?: 0.0)}"
                    }
                },
                onFailure = { chatResultText = "Couldn't save: ${it.message}" }
            )
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

private fun parseChatMeal(json: String): Pair<Int, List<Pair<String, String>>>? {
    val calls = JSONObject(json).optJSONArray("calls") ?: return null
    for (i in 0 until calls.length()) {
        val call = calls.getJSONObject(i)
        if (!call.optString("name").endsWith("log_meal")) continue
        val output = call.optJSONObject("output") ?: continue
        val logged = output.optJSONArray("logged") ?: continue
        if (!output.has("event_id")) continue
        val items = (0 until logged.length()).map {
            val o = logged.getJSONObject(it)
            o.optString("name") to o.optDouble("grams").toInt().toString()
        }
        return output.optInt("event_id") to items
    }
    return null
}

internal fun optionalDouble(obj: JSONObject?, key: String): Double? {
    if (obj == null || !obj.has(key) || obj.isNull(key)) return null
    return obj.optDouble(key)
}

