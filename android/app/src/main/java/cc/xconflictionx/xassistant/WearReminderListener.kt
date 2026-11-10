package cc.xconflictionx.xassistant

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

class WearReminderListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/xassistant/reminder") return
        val parts = event.data.toString(Charsets.UTF_8).split('\n', limit = 2)
        val transcript = parts.firstOrNull()?.trim().orEmpty()
        if (transcript.isBlank()) return
        val requestId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<WearReminderWorker>()
            .setInputData(workDataOf(
                WearReminderWorker.KEY_TRANSCRIPT to transcript,
                WearReminderWorker.KEY_NODE_ID to event.sourceNodeId,
                WearReminderWorker.KEY_REQUEST_ID to requestId
            )).build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}