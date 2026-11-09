package cc.xconflictionx.xassistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ReminderScreen() } } }
    }

    @Composable
    private fun ReminderScreen() {
        var transcript by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<ReminderResult?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Speak or type a reminder.") }
        val scope = rememberCoroutineScope()
        val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            if (activityResult.resultCode == RESULT_OK) {
                val recognized = activityResult.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (recognized != null) {
                    transcript = recognized
                    result = null
                    message = "Transcript captured. Review it, then send."
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("xAssistant", style = MaterialTheme.typography.headlineLarge)
            Text("Your reminders, understood naturally.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Original transcript") },
                placeholder = { Text("Remind me to…") },
                minLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reminder")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    try { speechLauncher.launch(intent) }
                    catch (_: Exception) { message = "No compatible speech-recognition app is available." }
                }, enabled = !busy) { Text("🎙 Speak") }

                Button(onClick = {
                    val exactTranscript = transcript
                    if (exactTranscript.isNotBlank()) {
                        busy = true
                        result = null
                        message = "Sending recognized text to xServer…"
                        scope.launch {
                            try {
                                result = ReminderApi().interpret(exactTranscript)
                                message = when (result?.status) {
                                    "interpreted" -> "Interpretation received — not saved yet."
                                    "clarification_required" -> "This request needs clarification."
                                    else -> "The request was not accepted."
                                }
                            } catch (error: Exception) {
                                message = "Could not reach xServer: ${error.message ?: "unknown error"}"
                            } finally { busy = false }
                        }
                    }
                }, enabled = !busy && transcript.isNotBlank()) { Text(if (busy) "Working…" else "Interpret") }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            result?.let { response ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Server response", style = MaterialTheme.typography.titleMedium)
                        Text("Original text: ${response.originalTranscript}")
                        response.title?.let { Text("Reminder: $it") }
                        response.scheduledAt?.let { Text("When: $it") }
                        response.recurrence?.let { Text("Repeats: $it") }
                        response.clarification?.let { Text("Question: $it") }
                        response.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                        if (response.status == "interpreted") Text("Interpretation only. The current server endpoint does not save reminders.")
                    }
                }
            }
            Text("Speech is converted to text on this device. xServer receives the text only.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
