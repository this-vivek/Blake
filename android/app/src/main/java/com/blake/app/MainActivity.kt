package com.blake.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "blake_prefs"
private const val KEY_BASE_URL = "base_url"
private const val KEY_API_KEY = "api_key"
private const val DEFAULT_BASE_URL = "https://blake-assistant.onrender.com"

class ChatViewModel : ViewModel() {
    var responseText by mutableStateOf("")
    var isLoading by mutableStateOf(false)

    fun send(context: Context, text: String, imageBytes: ByteArray?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val api = BlakeApi(baseUrl, apiKey)

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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen()
                }
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Blake", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }

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

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var baseUrl by remember { mutableStateOf(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL) }
    var apiKey by remember { mutableStateOf(prefs.getString(KEY_API_KEY, "") ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putString(KEY_BASE_URL, baseUrl.trimEnd('/'))
                    .putString(KEY_API_KEY, apiKey)
                    .apply()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
