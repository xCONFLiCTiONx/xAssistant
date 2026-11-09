package cc.xconflictionx.xassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ReminderResult(
    val status: String,
    val originalTranscript: String,
    val title: String? = null,
    val scheduledAt: String? = null,
    val recurrence: String? = null,
    val clarification: String? = null,
    val error: String? = null
)

class ReminderApi(private val baseUrl: String = BuildConfig.SERVER_BASE_URL) {
    suspend fun interpret(transcript: String): ReminderResult = withContext(Dispatchers.IO) {
        val connection = URL("${baseUrl.trimEnd('/')}/v1/reminders/interpret").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("request_id", UUID.randomUUID().toString()).put("transcript", transcript).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("xServer returned HTTP $code: $responseText")
            val json = JSONObject(responseText)
            val interpretation = json.optJSONObject("interpretation")
            ReminderResult(
                status = json.optString("status", "rejected"),
                originalTranscript = json.optString("original_transcript", transcript),
                title = interpretation?.optString("title")?.takeUnless { it == "null" || it.isBlank() },
                scheduledAt = interpretation?.optString("scheduled_at")?.takeUnless { it == "null" || it.isBlank() },
                recurrence = interpretation?.optString("recurrence")?.takeUnless { it == "null" || it.isBlank() },
                clarification = interpretation?.optString("clarification_question")?.takeUnless { it == "null" || it.isBlank() },
                error = json.optString("error").takeUnless { it == "null" || it.isBlank() }
            )
        } finally { connection.disconnect() }
    }
}
