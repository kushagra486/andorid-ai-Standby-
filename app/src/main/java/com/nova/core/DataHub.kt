package com.nova.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import com.nova.storage.ClockFormat
import com.nova.storage.Prefs
import java.util.Date

data class BatteryInfo(val percent: Int = -1, val charging: Boolean = false, val plugged: Boolean = false, val tempC: Float = Float.NaN)
data class WifiInfo(val enabled: Boolean = false, val connected: Boolean = false, val name: String = "", val signal: Int = 0, val online: Boolean = false)
data class BluetoothInfo(val available: Boolean = false, val enabled: Boolean = false, val connectedName: String = "", val paired: List<String> = emptyList(), val permission: Boolean = true)
data class WeatherInfo(
    val place: String, val tempC: Double, val code: Int, val isDay: Boolean,
    val highC: Double, val lowC: Double, val windKmh: Double, val updatedAt: Long,
    val hourly: List<Pair<Int, Double>> = emptyList(),
)
data class CalendarEvent(val title: String, val start: Long, val end: Long, val allDay: Boolean, val location: String)
data class MediaInfo(
    val app: String, val packageName: String, val title: String, val artist: String, val album: String,
    val playing: Boolean, val positionMs: Long, val durationMs: Long, val positionAt: Long, val art: Bitmap?,
)
data class NotificationItem(val app: String, val title: String, val text: String, val time: Long, val key: String)
data class TimerInfo(val id: Long, val label: String, val endsAt: Long, val totalMs: Long)
data class Reminder(val id: Long, val text: String, val at: Long, val done: Boolean)

/**
 * Single in-memory snapshot of everything the widgets show. Providers write into it on the
 * main thread and call [changed]; views redraw. Nothing here does I/O itself.
 */
object DataHub {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    var battery = BatteryInfo()
    var wifi = WifiInfo()
    var bluetooth = BluetoothInfo()
    var weather: WeatherInfo? = null
    var weatherStatus = "Set a location in Settings → Weather"
    var events: List<CalendarEvent> = emptyList()
    var calendarStatus = "Allow calendar access in Settings"
    var media: MediaInfo? = null
    var mediaStatus = "Enable notification access to see music"
    var audioRoute = "Phone speaker"
    var notifications: List<NotificationItem> = emptyList()
    var timers: List<TimerInfo> = emptyList()
    var reminders: List<Reminder> = emptyList()
    var spotifyDevice = ""

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun changed() {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.toList().forEach { it() }
        else main.post { listeners.toList().forEach { it() } }
    }

    /** Live media position, extrapolated while playing. */
    fun mediaPosition(now: Long = System.currentTimeMillis()): Long {
        val m = media ?: return 0
        return if (m.playing) (m.positionMs + (now - m.positionAt)).coerceAtMost(m.durationMs.coerceAtLeast(0)) else m.positionMs
    }
}

/** Clock text helpers honouring the 12/24-hour preference. */
object Clock {
    fun is24(context: Context): Boolean = when (Prefs.clockFormat) {
        ClockFormat.AUTO -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }

    fun time(context: Context, d: Date = Date(), seconds: Boolean = false): String {
        val f = if (is24(context)) (if (seconds) "HH:mm:ss" else "HH:mm") else (if (seconds) "h:mm:ss" else "h:mm")
        return DateFormat.format(f, d).toString()
    }

    fun amPm(context: Context, d: Date = Date()): String =
        if (is24(context)) "" else DateFormat.format("a", d).toString().uppercase()

    fun date(d: Date = Date(), pattern: String = "EEEE, d MMMM"): String = DateFormat.format(pattern, d).toString()
}
