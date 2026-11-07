package com.nova.integrations

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.nova.MainActivity
import com.nova.core.DataHub
import com.nova.core.Reminder
import com.nova.core.TimerInfo
import com.nova.storage.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Timers and reminders, stored locally and scheduled with AlarmManager so they fire even if
 * NØVA is in the background. Uses exact alarms where permitted (API 31+ exact-alarm rules)
 * and falls back to inexact-but-allowed-while-idle alarms otherwise.
 */
object Alarms {
    private const val CHANNEL = "nova_alarms"
    var onFired: ((String) -> Unit)? = null

    fun load() {
        DataHub.timers = readTimers()
        DataHub.reminders = readReminders()
    }

    // ---- Timers -------------------------------------------------------------------------
    fun startTimer(context: Context, minutes: Double, label: String = ""): TimerInfo {
        val ms = (minutes * 60_000).toLong().coerceAtLeast(1_000)
        val t = TimerInfo(System.currentTimeMillis(), label.ifBlank { formatDuration(ms) + " timer" }, System.currentTimeMillis() + ms, ms)
        DataHub.timers = DataHub.timers + t
        saveTimers(); schedule(context, t.id, t.endsAt, "Timer done", t.label)
        DataHub.changed()
        return t
    }

    fun cancelTimer(context: Context, id: Long) {
        DataHub.timers = DataHub.timers.filter { it.id != id }
        saveTimers(); cancel(context, id); DataHub.changed()
    }

    fun clearFinished(context: Context) {
        val now = System.currentTimeMillis()
        DataHub.timers.filter { it.endsAt <= now }.forEach { cancel(context, it.id) }
        DataHub.timers = DataHub.timers.filter { it.endsAt > now }
        saveTimers(); DataHub.changed()
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} h ${(s / 60 % 60).let { if (it > 0) "$it min" else "" }}".trim()
            s >= 60 -> "${s / 60} min" + (if (s % 60 > 0) " ${s % 60} s" else "")
            else -> "$s s"
        }
    }

    // ---- Reminders ----------------------------------------------------------------------
    fun addReminder(context: Context, text: String, at: Long): Reminder {
        val r = Reminder(System.currentTimeMillis(), text, at, false)
        DataHub.reminders = DataHub.reminders + r
        saveReminders(); schedule(context, r.id, at, "Reminder", text)
        DataHub.changed()
        return r
    }

    fun completeReminder(context: Context, id: Long) {
        DataHub.reminders = DataHub.reminders.map { if (it.id == id) it.copy(done = true) else it }
        saveReminders(); cancel(context, id); DataHub.changed()
    }

    fun deleteReminder(context: Context, id: Long) {
        DataHub.reminders = DataHub.reminders.filter { it.id != id }
        saveReminders(); cancel(context, id); DataHub.changed()
    }

    // ---- Scheduling ---------------------------------------------------------------------
    private fun pending(context: Context, id: Long, title: String, text: String): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).putExtra("id", id).putExtra("title", title).putExtra("text", text)
        return PendingIntent.getBroadcast(context, (id % Int.MAX_VALUE).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(context: Context, id: Long, at: Long, title: String, text: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, id, title, text)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, id, "", ""))
    }

    /** Re-arm everything after a reboot or app update. */
    fun rescheduleAll(context: Context) {
        load()
        val now = System.currentTimeMillis()
        DataHub.timers.filter { it.endsAt > now }.forEach { schedule(context, it.id, it.endsAt, "Timer done", it.label) }
        DataHub.reminders.filter { !it.done && it.at > now }.forEach { schedule(context, it.id, it.at, "Reminder", it.text) }
    }

    fun notify(context: Context, id: Long, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers & reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            })
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).setPriority(Notification.PRIORITY_HIGH)
        val n = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { nm.notify((id % Int.MAX_VALUE).toInt(), n) }
    }

    // ---- Storage ------------------------------------------------------------------------
    private fun readTimers(): List<TimerInfo> = runCatching {
        val a = JSONArray(Prefs.raw.getString("timers", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> TimerInfo(o.getLong("id"), o.getString("label"), o.getLong("end"), o.getLong("total")) } }
    }.getOrDefault(emptyList())

    private fun saveTimers() {
        val a = JSONArray(); DataHub.timers.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("end", it.endsAt).put("total", it.totalMs)) }
        Prefs.raw.edit().putString("timers", a.toString()).apply()
    }

    private fun readReminders(): List<Reminder> = runCatching {
        val a = JSONArray(Prefs.raw.getString("reminders", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Reminder(o.getLong("id"), o.getString("text"), o.getLong("at"), o.optBoolean("done")) } }
    }.getOrDefault(emptyList())

    private fun saveReminders() {
        val a = JSONArray(); DataHub.reminders.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("done", it.done)) }
        Prefs.raw.edit().putString("reminders", a.toString()).apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        val id = intent.getLongExtra("id", 0)
        val title = intent.getStringExtra("title") ?: "NØVA"
        val text = intent.getStringExtra("text") ?: ""
        Alarms.notify(context, id, title, text)
        Alarms.load()
        DataHub.changed()
        Alarms.onFired?.invoke("$title: $text")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Alarms.rescheduleAll(context)
    }
}
