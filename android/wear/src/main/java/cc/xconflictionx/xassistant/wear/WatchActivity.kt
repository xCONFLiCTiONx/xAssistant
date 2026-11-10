package cc.xconflictionx.xassistant.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.UUID

class WatchActivity : ComponentActivity() {\n    private fun formatResult(raw: String): String {\n        val parts = raw.split("\\n", limit = 3)\n        return when (parts.firstOrNull()) {\n            "SAVED" -> "✓ Saved: " + parts.getOrNull(1).orEmpty()\n            "ERROR" -> "Not saved: " + parts.getOrNull(1).orEmpty()\n            else -> raw\n        }\n    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var status by remember { mutableStateOf(intent.getStringExtra("xassistant_result")?.let(::formatResult) ?: "Ready when you are.") }
                var transcript by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                        status = if (transcript.isBlank()) "I didn't catch that. Try again." else "Review, then send."
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        Text("xAssistant", style = MaterialTheme.typography.titleLarge)
                        Text(status, textAlign = TextAlign.Center)
                        if (transcript.isNotBlank()) Text(transcript, textAlign = TextAlign.Center)
                        Button(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reminder")
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            }
                            runCatching { speech.launch(intent) }.onFailure { status = "Speech recognition unavailable." }
                        }, enabled = !sending) { Text("Speak") }
                        OutlinedButton(onClick = {
                            val text = transcript.trim()
                            if (text.isNotEmpty()) scope.launch {
                                sending = true
                                status = "Sending to phone…"
                                try {
                                    val nodes = Wearable.getNodeClient(this@WatchActivity).connectedNodes.await()
                                    val node = nodes.firstOrNull() ?: error("No paired phone is connected.")
                                    val payload = (text + "\n" + UUID.randomUUID()).toByteArray(Charsets.UTF_8)
                                    Wearable.getMessageClient(this@WatchActivity).sendMessage(node.id, "/xassistant/reminder", payload).await()
                                    status = "Sent. Waiting for xServer confirmation."
                                    transcript = ""
                                } catch (e: Exception) { status = e.message ?: "Phone unavailable. Nothing was saved." }
                                finally { sending = false }
                            }
                        }, enabled = transcript.isNotBlank() && !sending) { Text("Send reminder") }
                    }
                }
            }
        }
    }
}