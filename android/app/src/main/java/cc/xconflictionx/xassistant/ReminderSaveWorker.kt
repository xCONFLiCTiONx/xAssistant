package cc.xconflictionx.xassistant

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ReminderSaveWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    override fun doWork(): Result {
        val transcript = inputData.getString(KEY_TRANSCRIPT)?.trim().orEmpty()
        val baseUrl = inputData.getString(KEY_BASE_URL).orEmpty().trimEnd('/')
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: UUID.randomUUID().toString()

        if (transcript.isBlank() || baseUrl.isBlank()) {
            return Result.failure(output("rejected", transcript, "Missing reminder text or server address."))
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$baseUrl/v1/reminders").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject()
                .put("request_id", requestId)
                .put("transcript", transcript)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return Result.failure(output("rejected", transcript, "xServer returned HTTP $code."))
            }

            val response = JSONObject(responseText)
            val status = response.optString("status", "rejected")
            val original = response.optString("original_transcript", transcript)
            val error = response.optString("error").takeUnless { it.isBlank() || it == "null" }

            if (status == "saved") {
                val reminder = response.optJSONObject("reminder")
                    ?: return Result.failure(output("rejected", original, "The server did not return the saved reminder."))
                val id = reminder.optInt("id", -1)
                val title = reminder.optString("title")
                val scheduledAt = reminder.optString("scheduled_at")
                if (id <= 0 || title.isBlank() || scheduledAt.isBlank()) {
                    return Result.failure(output("rejected", original, "The server returned incomplete reminder details."))
                }
                ReminderAlarmScheduler.schedule(applicationContext, id, title, scheduledAt)
                ReminderAlarmScheduler.showSavedNotification(applicationContext, id, title, scheduledAt)
                Result.success(androidx.work.Data.Builder()
                    .putString(KEY_STATUS, "saved")
                    .putString(KEY_ORIGINAL, original)
                    .putInt(KEY_REMINDER_ID, id)
                    .putString(KEY_TITLE, title)
                    .putString(KEY_SCHEDULED_AT, scheduledAt)
                    .build())
            } else {
                Result.success(output(status, original, error ?: when (status) {
                    "clarification_required" -> response.optJSONObject("interpretation")
                        ?.optString("clarification_question")
                    else -> "The reminder could not be saved."
                }))
            }
        } catch (error: IOException) {
            if (runAttemptCount < 5) Result.retry()
            else Result.failure(output("rejected", transcript, "Could not reach xServer after several attempts."))
        } catch (error: Exception) {
            Result.failure(output("rejected", transcript, error.message ?: "Unexpected save error."))
        } finally {
            connection?.disconnect()
        }
    }

    private fun output(status: String, original: String, error: String?) =
        androidx.work.Data.Builder()
            .putString(KEY_STATUS, status)
            .putString(KEY_ORIGINAL, original)
            .putString(KEY_ERROR, error)
            .build()

    companion object {
        const val KEY_TRANSCRIPT = "transcript"
        const val KEY_BASE_URL = "base_url"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_STATUS = "status"
        const val KEY_ORIGINAL = "original_transcript"
        const val KEY_ERROR = "error"
        const val KEY_REMINDER_ID = "reminder_id"
        const val KEY_TITLE = "title"
        const val KEY_SCHEDULED_AT = "scheduled_at"
    }
}
