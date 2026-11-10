package cc.xconflictionx.xassistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class WearReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val transcript = inputData.getString(KEY_TRANSCRIPT) ?: return Result.failure()
        val nodeId = inputData.getString(KEY_NODE_ID) ?: return Result.failure()
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.failure()
        val result = try { ReminderApi().create(transcript, requestId) }
        catch (error: Exception) {
            if (runAttemptCount < 3) return Result.retry()
            send(nodeId, "ERROR\n${error.message ?: "xServer could not save the reminder."}\n$transcript")
            return Result.failure()
        }
        val response = if (result.status == "saved")
            "SAVED\n${result.title.orEmpty()}\n${result.scheduledAt.orEmpty()}"
        else "ERROR\n${result.error ?: result.clarification ?: "The reminder was not saved."}\n${result.originalTranscript}"
        send(nodeId, response)
        return Result.success()
    }

    private suspend fun send(nodeId: String, response: String) {
        runCatching { Wearable.getMessageClient(applicationContext)
            .sendMessage(nodeId, "/xassistant/result", response.toByteArray(Charsets.UTF_8)).await() }
    }

    companion object {
        const val KEY_TRANSCRIPT = "transcript"
        const val KEY_NODE_ID = "node_id"
        const val KEY_REQUEST_ID = "request_id"
    }
}