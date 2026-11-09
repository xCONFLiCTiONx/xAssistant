package cc.xconflictionx.xassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ReminderResult(
    val status: String,
    val originalTranscript: String,
    val reminderId: Int? = null,
    val title: String? = null,
    val scheduledAt: String? = null,
    val recurrence: String? = null,
    val clarification: String? = null,
    val error: String? = null
)

data class ReminderItem(
    val id: Int,
    val title: String,
    val scheduledAt: String,
    val recurrence: String?,
    val status: String
)

class ReminderApi(private val baseUrl: String = BuildConfig.SERVER_BASE_URL) {
    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val connection = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("xServer returned HTTP $code: $response")
                response
            } finally { connection.disconnect() }
        }

    suspend fun create(transcript: String): ReminderResult {
        val json = JSONObject(request(
            "/v1/reminders", "POST",
            JSONObject().put("request_id", UUID.randomUUID().toString()).put("transcript", transcript)
        ))
        val reminder = json.optJSONObject("reminder")
        val interpretation = json.optJSONObject("interpretation")
        return ReminderResult(
            status = json.optString("status", "rejected"),
            originalTranscript = json.optString("original_transcript", transcript),
            reminderId = reminder?.optInt("id"),
            title = reminder?.optString("title")?.takeUnless { it.isNullOrBlank() }
                ?: interpretation?.optString("title")?.takeUnless { it.isNullOrBlank() },
            scheduledAt = reminder?.optString("scheduled_at")?.takeUnless { it.isNullOrBlank() }
                ?: interpretation?.optString("scheduled_at")?.takeUnless { it.isNullOrBlank() },
            recurrence = reminder?.optString("recurrence")?.takeUnless { it == "null" || it.isBlank() }
                ?: interpretation?.optString("recurrence")?.takeUnless { it == "null" || it.isBlank() },
            clarification = interpretation?.optString("clarification_question")?.takeUnless { it == "null" || it.isBlank() },
            error = json.optString("error").takeUnless { it == "null" || it.isBlank() }
        )
    }

    suspend fun list(): List<ReminderItem> {
        val array = JSONArray(request("/v1/reminders"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ReminderItem(
                id = item.getInt("id"),
                title = item.getString("title"),
                scheduledAt = item.getString("scheduled_at"),
                recurrence = item.optString("recurrence").takeUnless { it == "null" || it.isBlank() },
                status = item.getString("status")
            )
        }
    }

    suspend fun cancel(id: Int) { request("/v1/reminders/$id", "DELETE") }
    suspend fun complete(id: Int) { request("/v1/reminders/$id/complete", "POST") }
}
