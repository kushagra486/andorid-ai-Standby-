package com.nova.integrations

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.nova.core.CalendarEvent
import com.nova.core.DataHub

/** Next events from the phone's calendars — only after the user grants READ_CALENDAR. */
object CalendarSource {
    fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun refresh(context: Context) {
        if (!granted(context)) {
            DataHub.events = emptyList(); DataHub.calendarStatus = "Tap to allow calendar access"
            DataHub.changed(); return
        }
        val app = context.applicationContext
        Net.bg.execute {
            val now = System.currentTimeMillis()
            val list = ArrayList<CalendarEvent>()
            val result = runCatching {
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, now - 60 * 60_000L)
                    ContentUris.appendId(it, now + 36 * 60 * 60_000L)
                }.build()
                app.contentResolver.query(uri, arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                ), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                    while (c.moveToNext() && list.size < 12) {
                        val end = c.getLong(2)
                        if (end < now) continue
                        list += CalendarEvent(c.getString(0) ?: "(No title)", c.getLong(1), end, c.getInt(3) == 1, c.getString(4) ?: "")
                    }
                }
            }
            DataHub.events = list
            DataHub.calendarStatus = when {
                result.isFailure -> "Calendar unavailable"
                list.isEmpty() -> "No upcoming events"
                else -> ""
            }
            DataHub.changed()
        }
    }
}
