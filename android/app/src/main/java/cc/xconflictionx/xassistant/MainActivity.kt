package cc.xconflictionx.xassistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private val xAssistantDarkColors = darkColorScheme(
    primary = Color(0xFFB6C7FF), onPrimary = Color(0xFF172B55),
    secondary = Color(0xFFBFC7DC), onSecondary = Color(0xFF293144),
    background = Color(0xFF101318), onBackground = Color(0xFFE2E6EF),
    surface = Color(0xFF191E27), onSurface = Color(0xFFE2E6EF),
    surfaceVariant = Color(0xFF29313D), onSurfaceVariant = Color(0xFFC2C9D6),
    outline = Color(0xFF8993A5), error = Color(0xFFFFB4AB)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 19, 24)
        window.navigationBarColor = android.graphics.Color.rgb(16, 19, 24)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            MaterialTheme(colorScheme = xAssistantDarkColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                        WatchReminderScreen()
                    } else {
                        ReminderScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun ReminderScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val api = remember { ReminderApi() }
        val workManager = remember { WorkManager.getInstance(context) }
        var transcript by remember { mutableStateOf("") }
        var reminders by remember { mutableStateOf<List<ReminderItem>>(emptyList()) }
        var message by remember { mutableStateOf("Speak or type a reminder.") }
        var busy by remember { mutableStateOf(false) }
        var failureOriginal by remember { mutableStateOf<String?>(null) }
        var failureReason by remember { mutableStateOf<String?>(null) }
        var activeWorkId by remember { mutableStateOf(UUID(0L, 0L)) }
        var notificationsAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) }
        var exactAlarmsAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()) }
        var batteryOptimizationIgnored by remember { mutableStateOf(
            (context.getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
        ) }

        val workInfo by workManager.getWorkInfoByIdLiveData(activeWorkId).observeAsState()
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { notificationsAllowed = it }

        fun refreshPermissionState() {
            notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            exactAlarmsAllowed = Build.VERSION.SDK_INT < 31 ||
                (context.getSystemService(ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
            batteryOptimizationIgnored = (context.getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
        }

        DisposableEffect(Unit) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshPermissionState()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        suspend fun refreshReminders() {
            val items = api.list(includeInactive = true)
            reminders = items
            items.forEach {
                if (it.status == "active") {
                    runCatching { ReminderAlarmScheduler.schedule(context, it.id, it.title, it.scheduledAt) }
                } else {
                    ReminderAlarmScheduler.cancel(context, it.id)
                }
            }
        }

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { activityResult ->
            if (activityResult.resultCode == RESULT_OK) {
                val recognized = activityResult.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (recognized != null) {
                    transcript = recognized
                    failureOriginal = null
                    failureReason = null
                    message = "Transcript captured. Review it, then save."
                }
            }
        }

        LaunchedEffect(Unit) {
            try {
                refreshReminders()
                message = "Your reminders are up to date."
            } catch (_: Exception) {
                message = "xServer is unavailable. A new reminder will wait until a connection is available."
            }
            if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ReminderAlarmScheduler.restoreAll(context)
        }

        LaunchedEffect(workInfo?.id, workInfo?.state) {
            val info = workInfo ?: return@LaunchedEffect
            if (info.state.isFinished) {
                busy = false
                val data = info.outputData
                val status = data.getString(ReminderSaveWorker.KEY_STATUS).orEmpty()
                if (info.state == WorkInfo.State.SUCCEEDED && status == "saved") {
                    transcript = ""
                    failureOriginal = null
                    failureReason = null
                    message = "Reminder saved."
                    runCatching { refreshReminders() }
                } else {
                    failureOriginal = data.getString(ReminderSaveWorker.KEY_ORIGINAL) ?: transcript
                    failureReason = data.getString(ReminderSaveWorker.KEY_ERROR) ?: "The reminder could not be saved."
                    message = "Reminder not saved."
                }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("xAssistant", style = MaterialTheme.typography.headlineLarge)
            Text("Your reminders, understood naturally.", style = MaterialTheme.typography.bodyLarge)

            if (!notificationsAllowed) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Notifications are disabled. Android cannot alert you when reminders are due.")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33)
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("Enable notifications") }
                    }
                }
            }
            if (!exactAlarmsAllowed) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Allow exact alarms so reminders can notify you on time, including during Doze.")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                        }) { Text("Allow exact alarms") }
                    }
                }
            }
            if (!batteryOptimizationIgnored) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("For the most reliable background reminder saving and delivery, exclude xAssistant from battery optimization. Android will still manage normal app activity.")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:$packageName")))
                            }
                        }) { Text("Disable battery optimization") }
                    }
                }
            }

            OutlinedTextField(value = transcript,
                onValueChange = { transcript = it; failureOriginal = null; failureReason = null },
                modifier = Modifier.fillMaxWidth(), label = { Text("Reminder") },
                placeholder = { Text("Remind me to…") }, minLines = 3, enabled = !busy)

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
                    val exactTranscript = transcript.trim()
                    if (exactTranscript.isNotBlank() && !busy) {
                        failureOriginal = null
                        failureReason = null
                        busy = true
                        message = "Saving on xServer… This continues if you leave the app."
                        val request = OneTimeWorkRequestBuilder<ReminderSaveWorker>()
                            .setInputData(workDataOf(
                                ReminderSaveWorker.KEY_TRANSCRIPT to exactTranscript,
                                ReminderSaveWorker.KEY_BASE_URL to BuildConfig.SERVER_BASE_URL,
                                ReminderSaveWorker.KEY_REQUEST_ID to UUID.randomUUID().toString()))
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                            .addTag("xassistant-reminder-save").build()
                        activeWorkId = request.id
                        workManager.enqueueUniqueWork("xassistant-save-" + request.id,
                            ExistingWorkPolicy.KEEP, request)
                    }
                }, enabled = !busy && transcript.isNotBlank()) {
                    Text(if (busy) "Saving…" else "Save reminder")
                }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)

            if (failureOriginal != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't save this reminder", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                        Text(failureReason ?: "Please try again.")
                        Text("Original: $failureOriginal")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("My reminders", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = {
                    scope.launch {
                        try { refreshReminders(); message = "Reminder list updated." }
                        catch (error: Exception) { message = "Could not refresh: ${error.message}" }
                    }
                }, enabled = !busy) { Text("Refresh") }
            }

            if (reminders.isEmpty()) Text("No active reminders found.")
            else reminders.filter { it.status == "active" }.forEach { reminder ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(reminder.title, style = MaterialTheme.typography.titleMedium)
                        Text(ReminderTime.display(reminder.scheduledAt))
                        reminder.recurrence?.let { Text("Repeats: $it") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        api.complete(reminder.id)
                                        ReminderAlarmScheduler.cancel(context, reminder.id)
                                        refreshReminders()
                                        message = "Reminder completed."
                                    } catch (error: Exception) { message = "Update failed: ${error.message}" }
                                }
                            }) { Text("Complete") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        api.cancel(reminder.id)
                                        ReminderAlarmScheduler.cancel(context, reminder.id)
                                        refreshReminders()
                                        message = "Reminder cancelled."
                                    } catch (error: Exception) { message = "Cancel failed: ${error.message}" }
                                }
                            }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun WatchReminderScreen() {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    var transcript by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Starting voice input…") }
    var busy by remember { mutableStateOf(false) }
    var activeWorkId by remember { mutableStateOf(UUID(0L, 0L)) }
    val workInfo by workManager.getWorkInfoByIdLiveData(activeWorkId).observeAsState()

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty()
            status = if (transcript.isBlank()) "Didn't catch that. Listen again."
                else "Review your reminder, then send."
        } else {
            status = "Ready when you are. Tap Listen again."
        }
    }

    fun listenAgain() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your reminder")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        status = "Listening…"
        try {
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            status = "Speech recognition isn't available."
        }
    }

    LaunchedEffect(Unit) { listenAgain() }

    LaunchedEffect(workInfo?.id, workInfo?.state) {
        val info = workInfo ?: return@LaunchedEffect
        if (info.state.isFinished) {
            busy = false
            val data = info.outputData
            if (info.state == WorkInfo.State.SUCCEEDED &&
                data.getString(ReminderSaveWorker.KEY_STATUS) == "saved") {
                val title = data.getString(ReminderSaveWorker.KEY_TITLE).orEmpty()
                transcript = ""
                status = if (title.isBlank()) "Reminder sent and saved." else "Saved: $title"
            } else {
                status = data.getString(ReminderSaveWorker.KEY_ERROR)
                    ?: "Reminder wasn't saved. Listen again to retry."
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text("xAssistant", style = MaterialTheme.typography.titleLarge)
        Text(status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (transcript.isNotBlank()) {
            Text(transcript, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = transcript.isNotBlank() && !busy,
            onClick = {
                val exactTranscript = transcript.trim()
                if (exactTranscript.isNotBlank() && !busy) {
                    busy = true
                    status = "Sending to xServer…"
                    val request = OneTimeWorkRequestBuilder<ReminderSaveWorker>()
                        .setInputData(workDataOf(
                            ReminderSaveWorker.KEY_TRANSCRIPT to exactTranscript,
                            ReminderSaveWorker.KEY_BASE_URL to BuildConfig.SERVER_BASE_URL,
                            ReminderSaveWorker.KEY_REQUEST_ID to UUID.randomUUID().toString()
                        ))
                        .setConstraints(Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag("xassistant-reminder-save")
                        .build()
                    activeWorkId = request.id
                    workManager.enqueueUniqueWork(
                        "xassistant-save-${request.id}", ExistingWorkPolicy.KEEP, request
                    )
                }
            }
        ) {
            Text(if (busy) "Sending…" else "Send reminder")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { listenAgain() }
        ) {
            Text("Listen again")
        }
    }
}
