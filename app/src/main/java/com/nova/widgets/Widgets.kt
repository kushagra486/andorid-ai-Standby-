package com.nova.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import com.nova.core.Clock
import com.nova.core.DataHub
import com.nova.integrations.Weather
import com.nova.storage.Prefs
import com.nova.storage.TempUnit
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import java.util.Date
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Widget sizes as grid spans (columns × rows) — never fixed pixels (spec v1.1 §14.5). */
enum class TileSize(val label: String, val cols: Int, val rows: Int) {
    SMALL("Small", 1, 1), MEDIUM("Medium", 2, 1), LARGE("Large", 2, 2), FULL("Full screen", 99, 2);
}

/** The initial widget catalogue. */
enum class WidgetType(val label: String) {
    CLOCK("Clock"), DATE("Date"), WEATHER("Weather"), CALENDAR("Next event"), BATTERY("Battery"),
    WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), MUSIC("Music"), REMINDERS("Reminders"), TIMER("Timer"),
    NOTIFICATIONS("Notifications"), PET("Pixel Pet status");

    companion object { fun byName(n: String) = entries.firstOrNull { it.name == n } }
}

/** Draws any widget into a rectangle. Stateless apart from reusable paints. */
class WidgetRenderer(private val context: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()

    /** Mood line for the Pixel Pet widget, supplied by the app. */
    var petMood: () -> String = { "Idle" }

    fun drawTile(c: Canvas, r: RectF, radius: Float) {
        fill.color = Look.c(ColorRole.TILE)
        c.drawRoundRect(r, radius, radius, fill)
        stroke.color = Look.c(ColorRole.TILE_BORDER); stroke.strokeWidth = 1.5f
        c.drawRoundRect(r, radius, radius, stroke)
    }

    fun draw(c: Canvas, type: WidgetType, r: RectF) {
        val small = r.width() < r.height() * 1.35f && r.height() < c.height * 0.6f
        val big = r.height() > c.height * 0.55f
        when (type) {
            WidgetType.CLOCK -> clock(c, r, small)
            WidgetType.DATE -> date(c, r)
            WidgetType.WEATHER -> weather(c, r, small, big)
            WidgetType.CALENDAR -> calendar(c, r, big)
            WidgetType.BATTERY -> battery(c, r, small)
            WidgetType.WIFI -> wifi(c, r)
            WidgetType.BLUETOOTH -> bluetooth(c, r, big)
            WidgetType.MUSIC -> music(c, r, small, big)
            WidgetType.REMINDERS -> reminders(c, r, big)
            WidgetType.TIMER -> timer(c, r)
            WidgetType.NOTIFICATIONS -> notifications(c, r, big)
            WidgetType.PET -> pet(c, r)
        }
    }

    // ---- Text helpers -------------------------------------------------------------------
    private fun t(c: Canvas, s: String, x: Float, y: Float, size: Float, role: ColorRole,
                  align: Paint.Align = Paint.Align.LEFT, face: android.graphics.Typeface = Theme.regular, maxW: Float = Float.MAX_VALUE) {
        text.textSize = size; text.typeface = face; text.textAlign = align; text.color = Look.c(role)
        val shown = if (maxW < Float.MAX_VALUE) TextUtils.ellipsize(s, text, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(shown, x, y, text)
    }

    private fun title(c: Canvas, r: RectF, s: String) =
        t(c, s.uppercase(), r.left + pad(r), r.top + pad(r) + r.height() * 0.06f, min(r.height() * 0.085f, 22f), ColorRole.TEXT_2, face = Theme.medium, maxW = r.width() - pad(r) * 2)

    private fun pad(r: RectF) = min(r.width(), r.height()) * 0.09f

    private fun fitText(s: String, maxW: Float, startSize: Float, face: android.graphics.Typeface): Float {
        text.typeface = face; text.textSize = startSize
        val w = text.measureText(s)
        return if (w > maxW) startSize * maxW / w else startSize
    }

    // ---- Widgets ------------------------------------------------------------------------
    private fun clock(c: Canvas, r: RectF, small: Boolean) {
        val d = Date()
        val time = Clock.time(context, d)
        val size = fitText(time, r.width() * 0.86f, r.height() * (if (small) 0.42f else 0.5f), Theme.light)
        t(c, time, r.centerX(), r.centerY() + size * 0.32f, size, ColorRole.CLOCK, Paint.Align.CENTER, Theme.light)
        val sub = (Clock.amPm(context, d).let { if (it.isEmpty()) "" else "$it  ·  " }) + Clock.date(d, if (small) "EEE d" else "EEEE, d MMM")
        t(c, sub, r.centerX(), r.bottom - pad(r), min(r.height() * 0.09f, 26f), ColorRole.TEXT_2, Paint.Align.CENTER, maxW = r.width() * 0.9f)
    }

    private fun date(c: Canvas, r: RectF) {
        val d = Date()
        t(c, DateFormat.format("EEEE", d).toString().uppercase(), r.centerX(), r.top + r.height() * 0.24f, min(r.height() * 0.1f, 26f), ColorRole.WARNING, Paint.Align.CENTER, Theme.medium)
        t(c, DateFormat.format("d", d).toString(), r.centerX(), r.centerY() + r.height() * 0.17f, r.height() * 0.42f, ColorRole.TEXT, Paint.Align.CENTER, Theme.light)
        t(c, DateFormat.format("MMMM yyyy", d).toString(), r.centerX(), r.bottom - pad(r), min(r.height() * 0.09f, 24f), ColorRole.TEXT_2, Paint.Align.CENTER)
    }

    private fun temp(cDeg: Double): String = if (Prefs.tempUnit == TempUnit.F) "${(cDeg * 9 / 5 + 32).roundToInt()}°" else "${cDeg.roundToInt()}°"

    private fun weather(c: Canvas, r: RectF, small: Boolean, big: Boolean) {
        val w = DataHub.weather
        if (w == null) {
            title(c, r, "Weather")
            weatherIcon(c, 2, true, r.centerX(), r.centerY() - r.height() * 0.04f, r.height() * 0.22f)
            t(c, DataHub.weatherStatus, r.centerX(), r.bottom - pad(r), min(r.height() * 0.07f, 20f), ColorRole.TEXT_2, Paint.Align.CENTER, maxW = r.width() * 0.9f)
            return
        }
        val p = pad(r)
        t(c, w.place, r.left + p, r.top + p + r.height() * 0.08f, min(r.height() * 0.1f, 26f), ColorRole.TEXT, face = Theme.medium, maxW = r.width() * 0.6f)
        val iconSize = r.height() * (if (big) 0.16f else 0.2f)
        weatherIcon(c, w.code, w.isDay, r.right - p - iconSize * 1.6f, r.top + p + iconSize * 1.3f, iconSize)
        val tempSize = r.height() * (if (big) 0.26f else 0.36f)
        t(c, temp(w.tempC), r.left + p, r.top + p + r.height() * 0.1f + tempSize, tempSize, ColorRole.TEXT, face = Theme.light)
        val desc = Weather.describe(w.code) + if (small) "" else "  ·  H ${temp(w.highC)}  L ${temp(w.lowC)}"
        t(c, desc, r.left + p, r.bottom - p - (if (big) r.height() * 0.32f else 0f), min(r.height() * 0.085f, 22f), ColorRole.TEXT_2, maxW = r.width() - p * 2)
        if (big && w.hourly.isNotEmpty()) {
            val n = min(6, w.hourly.size)
            val cw = (r.width() - p * 2) / n
            w.hourly.take(n).forEachIndexed { i, (hour, tc) ->
                val x = r.left + p + cw * i + cw / 2
                t(c, if (hour == 0) "12a" else if (hour < 12) "${hour}a" else if (hour == 12) "12p" else "${hour - 12}p",
                    x, r.bottom - p - r.height() * 0.17f, r.height() * 0.05f, ColorRole.TEXT_2, Paint.Align.CENTER)
                t(c, temp(tc), x, r.bottom - p, r.height() * 0.07f, ColorRole.TEXT, Paint.Align.CENTER)
            }
        }
    }

    /** Minimal line-art weather glyphs. WMO codes: 0 clear, 1–3 cloud, 45/48 fog, 5x/6x/8x rain, 7x snow, 95+ storm. */
    fun weatherIcon(c: Canvas, code: Int, day: Boolean, cx: Float, cy: Float, s: Float) {
        stroke.strokeWidth = s * 0.09f
        val sun = Look.c(ColorRole.WARNING); val cloud = Look.c(ColorRole.TEXT); val accent = Look.c(ColorRole.ACCENT)
        fun sunOrMoon(x: Float, y: Float, rad: Float) {
            if (day) {
                fill.color = sun; c.drawCircle(x, y, rad, fill)
                stroke.color = sun
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    c.drawLine(x + (cos(a) * rad * 1.4f).toFloat(), y + (sin(a) * rad * 1.4f).toFloat(),
                        x + (cos(a) * rad * 1.8f).toFloat(), y + (sin(a) * rad * 1.8f).toFloat(), stroke)
                }
            } else {
                fill.color = Look.c(ColorRole.TEXT); c.drawCircle(x, y, rad, fill)
                fill.color = Look.c(ColorRole.TILE); c.drawCircle(x + rad * 0.45f, y - rad * 0.35f, rad * 0.85f, fill)
            }
        }
        fun cloudShape(x: Float, y: Float, k: Float) {
            fill.color = cloud
            c.drawCircle(x - s * 0.35f * k, y, s * 0.3f * k, fill)
            c.drawCircle(x, y - s * 0.18f * k, s * 0.42f * k, fill)
            c.drawCircle(x + s * 0.38f * k, y + s * 0.02f * k, s * 0.28f * k, fill)
            tmp.set(x - s * 0.62f * k, y, x + s * 0.64f * k, y + s * 0.3f * k)
            c.drawRoundRect(tmp, s * 0.15f * k, s * 0.15f * k, fill)
        }
        when {
            code == 0 -> sunOrMoon(cx, cy, s * 0.42f)
            code in 1..3 -> { sunOrMoon(cx - s * 0.3f, cy - s * 0.3f, s * 0.3f); cloudShape(cx + s * 0.1f, cy + s * 0.1f, if (code == 3) 1f else 0.8f) }
            code == 45 || code == 48 -> { stroke.color = cloud; for (i in -1..1) c.drawLine(cx - s * 0.6f, cy + i * s * 0.3f, cx + s * 0.6f, cy + i * s * 0.3f, stroke) }
            code in 71..77 || code in 85..86 -> { cloudShape(cx, cy - s * 0.2f, 0.9f); fill.color = cloud; for (i in -1..1) c.drawCircle(cx + i * s * 0.32f, cy + s * 0.5f, s * 0.08f, fill) }
            code >= 95 -> {
                cloudShape(cx, cy - s * 0.25f, 0.9f)
                fill.color = sun; path.reset()
                path.moveTo(cx + s * 0.05f, cy + s * 0.05f); path.lineTo(cx - s * 0.2f, cy + s * 0.45f); path.lineTo(cx, cy + s * 0.45f)
                path.lineTo(cx - s * 0.12f, cy + s * 0.85f); path.lineTo(cx + s * 0.25f, cy + s * 0.32f); path.lineTo(cx + s * 0.03f, cy + s * 0.32f); path.close()
                c.drawPath(path, fill)
            }
            else -> { cloudShape(cx, cy - s * 0.2f, 0.9f); stroke.color = accent; for (i in -1..1) c.drawLine(cx + i * s * 0.32f, cy + s * 0.35f, cx + i * s * 0.32f - s * 0.1f, cy + s * 0.65f, stroke) }
        }
    }

    private fun calendar(c: Canvas, r: RectF, big: Boolean) {
        title(c, r, "Up next")
        val p = pad(r)
        val list = DataHub.events.filter { it.end > System.currentTimeMillis() }
        if (list.isEmpty()) {
            t(c, if (DataHub.calendarStatus.isNotEmpty()) DataHub.calendarStatus else "No more events today", r.left + p, r.centerY() + r.height() * 0.05f,
                min(r.height() * 0.1f, 24f), ColorRole.TEXT_2, maxW = r.width() - p * 2)
            return
        }
        val n = if (big) min(4, list.size) else 1
        val rowH = (r.height() - p * 2 - r.height() * 0.12f) / (if (big) 4 else 1)
        list.take(n).forEachIndexed { i, ev ->
            val y = r.top + p + r.height() * 0.14f + rowH * i
            fill.color = Look.c(if (i == 0) ColorRole.ACCENT else ColorRole.ACCENT_2)
            tmp.set(r.left + p, y + rowH * 0.12f, r.left + p + r.width() * 0.012f + 3, y + rowH * 0.88f)
            c.drawRoundRect(tmp, 3f, 3f, fill)
            val x = r.left + p + r.width() * 0.04f
            val sz = min(rowH * (if (big) 0.32f else 0.26f), 28f)
            t(c, ev.title, x, y + rowH * 0.42f, sz, ColorRole.TEXT, face = Theme.medium, maxW = r.right - p - x)
            val whenText = if (ev.allDay) "All day" else
                DateFormat.format(if (Clock.is24(context)) "HH:mm" else "h:mm a", ev.start).toString() + " – " +
                    DateFormat.format(if (Clock.is24(context)) "HH:mm" else "h:mm a", ev.end)
            t(c, whenText + (if (ev.location.isNotBlank()) "  ·  ${ev.location}" else ""), x, y + rowH * 0.42f + sz * 1.25f, sz * 0.8f, ColorRole.TEXT_2, maxW = r.right - p - x)
        }
    }

    private fun battery(c: Canvas, r: RectF, small: Boolean) {
        val b = DataHub.battery
        val size = min(r.height() * 0.62f, r.width() * (if (small) 0.6f else 0.42f))
        val cx = if (small) r.centerX() else r.left + r.width() * 0.3f
        val cy = r.centerY() - (if (small) r.height() * 0.06f else 0f)
        tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        stroke.strokeWidth = size * 0.11f
        stroke.color = Look.c(ColorRole.TILE_BORDER)
        c.drawArc(tmp, 0f, 360f, false, stroke)
        if (b.percent > 0) {
            stroke.color = Look.c(if (b.percent <= 20 && !b.charging) ColorRole.WARNING else ColorRole.BATTERY)
            c.drawArc(tmp, -90f, 360f * b.percent / 100f, false, stroke)
        }
        t(c, if (b.percent >= 0) "${b.percent}" else "—", cx, cy + size * 0.11f, size * 0.3f, ColorRole.TEXT, Paint.Align.CENTER, Theme.medium)
        val status = when { b.charging -> "Charging"; b.plugged -> "Plugged in"; b.percent >= 0 -> "On battery"; else -> "" }
        if (small) {
            t(c, status, r.centerX(), r.bottom - pad(r), min(r.height() * 0.09f, 20f), ColorRole.TEXT_2, Paint.Align.CENTER)
        } else {
            val tx = r.left + r.width() * 0.68f
            t(c, "Battery", tx, cy - r.height() * 0.02f, min(r.height() * 0.13f, 30f), ColorRole.TEXT, Paint.Align.CENTER, Theme.medium)
            t(c, status + if (!b.tempC.isNaN()) "  ·  ${b.tempC.roundToInt()}°C" else "", tx, cy + r.height() * 0.14f, min(r.height() * 0.1f, 22f), ColorRole.TEXT_2, Paint.Align.CENTER)
        }
    }

    private fun wifi(c: Canvas, r: RectF) {
        val w = DataHub.wifi
        val s = min(r.height(), r.width()) * 0.3f
        val cx = r.centerX(); val cy = r.centerY() + s * 0.1f
        stroke.strokeWidth = s * 0.14f
        for (i in 1..3) {
            val on = w.connected && w.signal >= i - 1
            stroke.color = Look.c(if (on) ColorRole.ACCENT else ColorRole.TILE_BORDER)
            val rad = s * 0.33f * i
            tmp.set(cx - rad, cy - rad, cx + rad, cy + rad)
            c.drawArc(tmp, 225f, 90f, false, stroke)
        }
        fill.color = Look.c(if (w.connected) ColorRole.ACCENT else ColorRole.TILE_BORDER)
        c.drawCircle(cx, cy, s * 0.1f, fill)
        val label = when { !w.enabled -> "Wi-Fi off"; !w.connected -> "Not connected"; w.name.isNotBlank() -> w.name; else -> "Connected" }
        t(c, label, cx, r.bottom - pad(r), min(r.height() * 0.1f, 22f), ColorRole.TEXT, Paint.Align.CENTER, maxW = r.width() * 0.9f)
        if (w.connected && !w.online) t(c, "No internet", cx, r.top + pad(r) + r.height() * 0.08f, min(r.height() * 0.08f, 18f), ColorRole.WARNING, Paint.Align.CENTER)
    }

    private fun bluetooth(c: Canvas, r: RectF, big: Boolean) {
        val b = DataHub.bluetooth
        val s = min(r.height(), r.width()) * 0.26f
        val cx = if (big) r.centerX() else r.centerX(); val cy = if (big) r.top + r.height() * 0.3f else r.centerY() - s * 0.2f
        stroke.strokeWidth = s * 0.12f
        stroke.color = Look.c(if (b.enabled) (if (b.connectedName.isNotBlank()) ColorRole.ACCENT else ColorRole.ACCENT_2) else ColorRole.TILE_BORDER)
        path.reset()
        path.moveTo(cx - s * 0.4f, cy - s * 0.4f); path.lineTo(cx + s * 0.4f, cy + s * 0.4f); path.lineTo(cx, cy + s * 0.8f)
        path.lineTo(cx, cy - s * 0.8f); path.lineTo(cx + s * 0.4f, cy - s * 0.4f); path.lineTo(cx - s * 0.4f, cy + s * 0.4f)
        c.drawPath(path, stroke.apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND })
        val label = when {
            !b.available -> "No Bluetooth"; !b.permission -> "Permission needed"; !b.enabled -> "Bluetooth off"
            b.connectedName.isNotBlank() -> b.connectedName; else -> "On · not connected"
        }
        t(c, label, r.centerX(), if (big) cy + s * 1.5f else r.bottom - pad(r), min(r.height() * (if (big) 0.06f else 0.1f), 22f), ColorRole.TEXT, Paint.Align.CENTER, maxW = r.width() * 0.9f)
        if (big && b.paired.isNotEmpty()) {
            b.paired.take(4).forEachIndexed { i, n ->
                t(c, n, r.centerX(), cy + s * 1.5f + r.height() * (0.09f + i * 0.08f), r.height() * 0.05f, ColorRole.TEXT_2, Paint.Align.CENTER, maxW = r.width() * 0.9f)
            }
        }
    }

    private fun music(c: Canvas, r: RectF, small: Boolean, big: Boolean) {
        val m = DataHub.media
        val p = pad(r)
        if (m == null) {
            title(c, r, "Music")
            noteIcon(c, r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.2f)
            t(c, DataHub.mediaStatus, r.centerX(), r.bottom - p, min(r.height() * 0.075f, 18f), ColorRole.TEXT_2, Paint.Align.CENTER, maxW = r.width() * 0.9f)
            return
        }
        val tall = r.height() > r.width() * 0.8f
        val art = m.art
        val x: Float; val maxW: Float; val y0: Float
        val sz: Float
        if (tall) {
            // Portrait tile: artwork on top, text underneath.
            val artSize = min(r.width() - p * 2, r.height() * 0.5f)
            tmp.set(r.centerX() - artSize / 2, r.top + p, r.centerX() + artSize / 2, r.top + p + artSize)
            drawArt(c, art, artSize)
            x = r.left + p; maxW = r.width() - p * 2
            sz = min(r.height() * 0.06f, 30f)
            y0 = tmp.bottom + sz * 1.6f
        } else {
            val artSize = if (small) 0f else r.height() - p * 2 - (if (big) r.height() * 0.3f else 0f)
            tmp.set(r.left + p, r.top + p, r.left + p + artSize, r.top + p + artSize)
            if (!small) drawArt(c, art, artSize)
            x = if (small) r.left + p else tmp.right + p
            maxW = r.right - p - x
            sz = min(r.height() * (if (big) 0.075f else 0.13f), 30f)
            y0 = if (small) r.top + p + sz else r.top + p + sz * 1.2f
        }
        t(c, m.title, x, y0, sz, ColorRole.TEXT, face = Theme.medium, maxW = maxW)
        t(c, m.artist, x, y0 + sz * 1.3f, sz * 0.85f, ColorRole.TEXT_2, maxW = maxW)
        t(c, (if (m.playing) "▶  " else "❚❚  ") + m.app, x, y0 + sz * 2.6f, sz * 0.7f, ColorRole.MUSIC, maxW = maxW)
        if (m.durationMs > 0 && !small) {
            val frac = (DataHub.mediaPosition().toFloat() / m.durationMs).coerceIn(0f, 1f)
            val by = r.bottom - p
            stroke.strokeWidth = r.height() * 0.02f; stroke.color = Look.c(ColorRole.TILE_BORDER)
            c.drawLine(x, by, x + maxW, by, stroke)
            stroke.color = Look.c(ColorRole.MUSIC)
            c.drawLine(x, by, x + maxW * frac, by, stroke)
        }
    }

    private fun drawArt(c: Canvas, art: android.graphics.Bitmap?, artSize: Float) {
        if (art != null && !art.isRecycled) {
            c.save(); c.clipPath(Path().apply { addRoundRect(tmp, artSize * 0.08f, artSize * 0.08f, Path.Direction.CW) })
            c.drawBitmap(art, null, tmp, fill.apply { color = 0xFFFFFFFF.toInt(); alpha = (255 * (1 - Look.night * 0.6f)).toInt() })
            c.restore(); fill.alpha = 255
        } else {
            fill.color = Look.c(ColorRole.TILE_BORDER); c.drawRoundRect(tmp, artSize * 0.08f, artSize * 0.08f, fill)
            noteIcon(c, tmp.centerX(), tmp.centerY(), artSize * 0.3f)
        }
    }

    private fun noteIcon(c: Canvas, cx: Float, cy: Float, s: Float) {
        fill.color = Look.c(ColorRole.MUSIC)
        c.drawCircle(cx - s * 0.35f, cy + s * 0.55f, s * 0.28f, fill)
        c.drawCircle(cx + s * 0.55f, cy + s * 0.4f, s * 0.28f, fill)
        stroke.color = fill.color; stroke.strokeWidth = s * 0.14f
        c.drawLine(cx - s * 0.1f, cy + s * 0.55f, cx - s * 0.1f, cy - s * 0.65f, stroke)
        c.drawLine(cx + s * 0.8f, cy + s * 0.4f, cx + s * 0.8f, cy - s * 0.8f, stroke)
        c.drawLine(cx - s * 0.1f, cy - s * 0.65f, cx + s * 0.8f, cy - s * 0.8f, stroke)
    }

    private fun reminders(c: Canvas, r: RectF, big: Boolean) {
        title(c, r, "Reminders")
        val p = pad(r)
        val list = DataHub.reminders.filter { !it.done }.sortedBy { it.at }
        if (list.isEmpty()) {
            t(c, "Nothing to remember", r.left + p, r.centerY() + r.height() * 0.06f, min(r.height() * 0.1f, 24f), ColorRole.TEXT_2, maxW = r.width() - p * 2)
            return
        }
        val n = if (big) min(5, list.size) else min(2, list.size)
        val rowH = (r.height() - p * 2 - r.height() * 0.12f) / n
        list.take(n).forEachIndexed { i, rem ->
            val y = r.top + p + r.height() * 0.14f + rowH * i + rowH * 0.6f
            stroke.color = Look.c(ColorRole.ACCENT); stroke.strokeWidth = 2.5f
            c.drawCircle(r.left + p + rowH * 0.18f, y - rowH * 0.15f, rowH * 0.14f, stroke)
            val x = r.left + p + rowH * 0.45f
            val sz = min(rowH * 0.36f, 26f)
            t(c, rem.text, x, y, sz, ColorRole.TEXT, maxW = r.right - p - x - r.width() * 0.22f)
            t(c, DateFormat.format(if (Clock.is24(context)) "EEE HH:mm" else "EEE h:mm a", rem.at).toString(), r.right - p, y, sz * 0.75f, ColorRole.TEXT_2, Paint.Align.RIGHT)
        }
    }

    private fun timer(c: Canvas, r: RectF) {
        val tm = DataHub.timers.minByOrNull { it.endsAt }
        val size = min(r.height() * 0.6f, r.width() * 0.6f)
        val cx = r.centerX(); val cy = r.centerY() - r.height() * 0.05f
        tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        stroke.strokeWidth = size * 0.08f; stroke.color = Look.c(ColorRole.TILE_BORDER)
        c.drawArc(tmp, 0f, 360f, false, stroke)
        if (tm == null) {
            t(c, "Timer", cx, cy + size * 0.06f, size * 0.18f, ColorRole.TEXT, Paint.Align.CENTER, Theme.medium)
            t(c, "Tap for 5 min", cx, r.bottom - pad(r), min(r.height() * 0.09f, 20f), ColorRole.TEXT_2, Paint.Align.CENTER)
            return
        }
        val left = (tm.endsAt - System.currentTimeMillis()).coerceAtLeast(0)
        stroke.color = Look.c(if (left == 0L) ColorRole.WARNING else ColorRole.ACCENT)
        c.drawArc(tmp, -90f, 360f * left / tm.totalMs.coerceAtLeast(1), false, stroke)
        val secs = (left + 999) / 1000
        val label = if (secs >= 3600) String.format("%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60) else String.format("%d:%02d", secs / 60, secs % 60)
        t(c, label, cx, cy + size * 0.08f, fitText(label, size * 0.7f, size * 0.24f, Theme.light), ColorRole.TEXT, Paint.Align.CENTER, Theme.light)
        t(c, if (left == 0L) "Done — tap to dismiss" else tm.label, cx, r.bottom - pad(r), min(r.height() * 0.09f, 20f), ColorRole.TEXT_2, Paint.Align.CENTER, maxW = r.width() * 0.9f)
    }

    private fun notifications(c: Canvas, r: RectF, big: Boolean) {
        title(c, r, "Notifications")
        val p = pad(r)
        val list = DataHub.notifications
        if (list.isEmpty()) {
            t(c, "All caught up", r.left + p, r.centerY() + r.height() * 0.06f, min(r.height() * 0.1f, 24f), ColorRole.TEXT_2, maxW = r.width() - p * 2)
            return
        }
        val n = if (big) min(4, list.size) else 1
        val rowH = (r.height() - p * 2 - r.height() * 0.12f) / (if (big) 4 else 1)
        list.take(n).forEachIndexed { i, item ->
            val y = r.top + p + r.height() * 0.14f + rowH * i
            val sz = min(rowH * (if (big) 0.3f else 0.24f), 26f)
            t(c, "${item.app}  ·  ${item.title}", r.left + p, y + sz * 1.2f, sz, ColorRole.TEXT, face = Theme.medium, maxW = r.width() - p * 2)
            t(c, item.text, r.left + p, y + sz * 2.5f, sz * 0.85f, ColorRole.TEXT_2, maxW = r.width() - p * 2)
        }
    }

    private fun pet(c: Canvas, r: RectF) {
        val s = min(r.width(), r.height()) * 0.22f
        fill.color = Look.c(ColorRole.EYES)
        for (dx in floatArrayOf(-0.6f, 0.6f)) {
            tmp.set(r.centerX() + dx * s - s * 0.35f, r.centerY() - s * 0.75f, r.centerX() + dx * s + s * 0.35f, r.centerY() + s * 0.45f)
            c.drawOval(tmp, fill)
        }
        fill.color = Look.c(ColorRole.MOUTH)
        tmp.set(r.centerX() - s * 0.3f, r.centerY() + s * 0.75f, r.centerX() + s * 0.3f, r.centerY() + s * 0.9f)
        c.drawRoundRect(tmp, s, s, fill)
        t(c, "NØVA · ${petMood()}", r.centerX(), r.bottom - pad(r), min(r.height() * 0.09f, 20f), ColorRole.TEXT_2, Paint.Align.CENTER, maxW = r.width() * 0.9f)
    }
}
