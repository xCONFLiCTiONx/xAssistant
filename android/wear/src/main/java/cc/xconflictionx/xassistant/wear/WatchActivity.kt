package cc.xconflictionx.xassistant.wear

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

class WatchActivity : ComponentActivity() {
    private fun formatResult(raw: String): String {
        val parts = raw.split("\n", limit = 3)
        return when (parts.firstOrNull()) {
            "SAVED" -> "✓ Saved: " + parts.getOrNull(1).orEmpty()
            "ERROR" -> "Not saved: " + parts.getOrNull(1).orEmpty()
            else -> raw
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incomingResult = intent.getStringExtra("xassistant_result")

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var status by remember(incomingResult) {
                    mutableStateOf(incomingResult?.let(::formatResult) ?: "Listening…")
                }
                var transcript by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                var assistantRoleHeld by remember {
                    mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true)
                }
                val assistantRoleRequest = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    assistantRoleHeld = getSystemService(RoleManager::class.java)
                        ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
                    status = if (assistantRoleHeld) "xAssistant is your default watch assistant."
                        else "xAssistant was not selected as the watch assistant."
                }

                val speech = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        transcript = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull().orEmpty()
                        status = if (transcript.isBlank()) {
                            "I didn't catch that. Tap Listen again."
                        } else {
                            "Review your reminder, then send."
                        }
                    } else {
                        status = "Tap Listen to try again."
                    }
                }

                fun startListening() {
                    val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE,
                            Locale.getDefault().toLanguageTag()
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reminder")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    runCatching { speech.launch(speechIntent) }
                        .onFailure { status = "Speech recognition unavailable." }
                }

                LaunchedEffect(Unit) {
                    if (incomingResult == null) startListening()
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("xAssistant", style = MaterialTheme.typography.titleMedium)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val roleManager = getSystemService(RoleManager::class.java)
                                    if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                                        assistantRoleRequest.launch(
                                            roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                                        )
                                    } else {
                                        status = "Choose xAssistant under Settings > Apps > Default apps > Digital assistant."
                                    }
                                }
                            ) {
                                Text(if (assistantRoleHeld) "Default assistant selected" else "Set as watch assistant")
                            }
                        }
                        Text(status, textAlign = TextAlign.Center)
                        if (transcript.isNotBlank()) {
                            Text(
                                transcript,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = { startListening() },
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Listen again")
                        }

                        Button(
                            onClick = {
                                val text = transcript.trim()
                                if (text.isNotEmpty()) scope.launch {
                                    sending = true
                                    status = "Sending to phone…"
                                    try {
                                        val nodes = Wearable
                                            .getNodeClient(this@WatchActivity)
                                            .connectedNodes.await()
                                        val node = nodes.firstOrNull()
                                            ?: error("No paired phone is connected.")
                                        val payload = (text + "\n" + UUID.randomUUID())
                                            .toByteArray(Charsets.UTF_8)
                                        Wearable.getMessageClient(this@WatchActivity)
                                            .sendMessage(
                                                node.id,
                                                "/xassistant/reminder",
                                                payload
                                            ).await()
                                        status = "Sent. Waiting for xServer confirmation."
                                        transcript = ""
                                    } catch (e: Exception) {
                                        status = e.message
                                            ?: "Phone unavailable. Nothing was saved."
                                    } finally {
                                        sending = false
                                    }
                                }
                            },
                            enabled = transcript.isNotBlank() && !sending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (sending) "Sending…" else "Send reminder")
                        }
                    }
                }
            }
        }
    }
}
