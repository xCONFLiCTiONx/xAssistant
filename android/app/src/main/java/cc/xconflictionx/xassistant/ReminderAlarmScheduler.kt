package cc.xconflictionx.xassistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import org.json.JSONObject
import java.time.OffsetDateTime

object ReminderAlarmScheduler {
    private const val PREFS = "xassistant_reminder_alarms"
    private const val CHANNEL_ID = "xassistant_reminders"

    fun schedule(context: Context, id: Int, title: String, scheduledAt: String) {
        val triggerAt = OffsetDateTime.parse(scheduledAt).toInstant().toEpochMilli()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "reminder:$id"
        val previous = prefs.getString(key, null)?.let { runCatching { JSONObject(it).optString("scheduled_at") }.getOrNull() }
        if (previous != null && previous != scheduledAt) {
            prefs.edit().remove("delivered:$id").apply()
        }
        if (prefs.getBoolean("delivered:$id", false)) return

        prefs.edit().putString(key, JSONObject()
            .put("id", id).put("title", title).put("scheduled_at", scheduledAt).toString()).apply()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmIntent(context, id, title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } else {
            // Fallback is inexact until the user grants exact-alarm access.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, id: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmIntent(context, id, ""))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("reminder:$id").remove("delivered:$id").apply()
    }

    fun restoreAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("reminder:") && value is String) {
                runCatching {
                    val item = JSONObject(value)
                    schedule(context, item.getInt("id"), item.getString("title"), item.getString("scheduled_at"))
                }
            }
        }
    }

    private fun alarmIntent(context: Context, id: Int, title: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(ReminderSaveWorker.KEY_REMINDER_ID, id)
            .putExtra(ReminderSaveWorker.KEY_TITLE, title)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showNotification(context: Context, id: Int, title: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (foreground) {
            prefs.edit().putBoolean("delivered:$id", true).apply()
            return
        }

        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Your reminder is due")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
            NotificationManagerCompat.from(context).notify(id, notification)
            prefs.edit().putBoolean("delivered:$id", true).apply()
        }
    }

    fun showSavedNotification(context: Context, id: Int, title: String, scheduledAt: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val channelId = "xassistant_save_confirmations"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                channelId, "Reminder confirmations", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Confirms when xAssistant successfully saves a reminder" })
        }
        val openApp = PendingIntent.getActivity(
            context, id + 1_000_000, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Reminder saved")
            .setContentText("$title · ${ReminderTime.display(scheduledAt)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$title\n${ReminderTime.display(scheduledAt)}"
            ))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(id + 1_000_000, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Scheduled xAssistant reminders" })
        }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ReminderSaveWorker.KEY_REMINDER_ID, -1)
        val title = intent.getStringExtra(ReminderSaveWorker.KEY_TITLE).orEmpty()
        if (id > 0 && title.isNotBlank()) {
            ReminderAlarmScheduler.showNotification(context, id, title)
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            ReminderAlarmScheduler.restoreAll(context)
        }
    }
}
