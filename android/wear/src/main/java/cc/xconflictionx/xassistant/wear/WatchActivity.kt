package cc.xconflictionx.xassistant.wear

import android.app.Activity
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Bundle
import android.os.Build
import android.provider.Settings
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
        val assistantComponent = ComponentName(
            this, XAssistantVoiceInteractionService::class.java
        ).flattenToShortString()
        registerAssistantIfPermitted(assistantComponent)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var status by remember(incomingResult) {
                    mutableStateOf(incomingResult?.let(::formatResult) ?: "Listening…")
                }
                var transcript by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                var assistantRegistered by remember {
                    mutableStateOf(
                        Settings.Secure.getString(
                            contentResolver, "voice_interaction_service"
                        ) == assistantComponent
                    )
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
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (registerAssistantIfPermitted(assistantComponent)) {
                                    assistantRegistered = true
                                    status = "xAssistant is registered as the watch assistant. Set Home button > Press and hold > Assistant."
                                } else {
                                    status = "Grant WRITE_SECURE_SETTINGS over ADB, then reopen xAssistant."
                                }
                            }
                        ) {
                            Text(if (assistantRegistered) "xAssistant is the default assistant" else "Register xAssistant as assistant")
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


class XAssistantVoiceInteractionService : VoiceInteractionService()

class XAssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return XAssistantVoiceInteractionSession(this)
    }
}

private fun WatchActivity.registerAssistantIfPermitted(component: String): Boolean {
    if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
        PackageManager.PERMISSION_GRANTED
    ) return false

    val assistant = Settings.Secure.putString(
        contentResolver, "assistant", component
    )
    val interaction = Settings.Secure.putString(
        contentResolver, "voice_interaction_service", component
    )
    return assistant && interaction
}

private class XAssistantVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context) {

    private val appContext = context.applicationContext

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val launchIntent = Intent(appContext, WatchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("cc.xconflictionx.xassistant.EXTRA_ASSISTANT_LAUNCH", true)
        }
        startAssistantActivity(launchIntent)
        hide()
    }
}
