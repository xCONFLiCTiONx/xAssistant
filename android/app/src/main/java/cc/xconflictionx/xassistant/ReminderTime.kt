package cc.xconflictionx.xassistant

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReminderTime {
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.getDefault())

    fun display(value: String): String = runCatching {
        OffsetDateTime.parse(value).toInstant()
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }.getOrDefault(value)
}
