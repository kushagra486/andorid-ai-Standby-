package com.nova.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.nova.core.Clock
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class ClockFace(val label: String) {
    DIGITAL("Digital"), ANALOG("Analog"), MINIMAL("Minimal"), FLIP("Flip"), NOVA("NØVA"), WORLD("Date & seconds");
}

/** Clock Gallery family: full-screen clock faces in NØVA's style (spec v1.1 §14.3). */
class ClockFaceView(context: Context) : View(context) {

    var face = ClockFace.DIGITAL
        set(value) { field = value; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            val smooth = face == ClockFace.ANALOG || face == ClockFace.NOVA || Look.transitioning
            postDelayed(this, if (smooth) 50L else 1_000L - System.currentTimeMillis() % 1_000 + 5)
        }
    }

    fun resume() { if (!running) { running = true; post(tick) } }
    fun pause() { running = false; removeCallbacks(tick) }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Look.c(ColorRole.BACKGROUND))
        when (face) {
            ClockFace.DIGITAL -> digital(canvas)
            ClockFace.ANALOG -> analog(canvas)
            ClockFace.MINIMAL -> minimal(canvas)
            ClockFace.FLIP -> flip(canvas)
            ClockFace.NOVA -> nova(canvas)
            ClockFace.WORLD -> dateSeconds(canvas)
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, role: ColorRole, face: android.graphics.Typeface, align: Paint.Align = Paint.Align.CENTER) {
        p.textSize = size; p.typeface = face; p.textAlign = align; p.color = Look.c(role); p.style = Paint.Style.FILL
        c.drawText(s, x, y, p)
    }

    private fun fit(s: String, maxW: Float, size: Float, face: android.graphics.Typeface): Float {
        p.typeface = face; p.textSize = size
        val w = p.measureText(s)
        return if (w > maxW) size * maxW / w else size
    }

    private fun digital(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = Date()
        val time = Clock.time(context, d, Prefs.showSeconds)
        val size = fit(time, w * 0.9f, h * 0.62f, Theme.light)
        text(c, time, w / 2, h * 0.5f + size * 0.34f, size, ColorRole.CLOCK, Theme.light)
        val sub = listOf(Clock.amPm(context, d), Clock.date(d)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        text(c, sub, w / 2, h * 0.9f, h * 0.06f, ColorRole.TEXT_2, Theme.regular)
    }

    private fun minimal(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = Date()
        text(c, Clock.time(context, d), w * 0.08f, h * 0.62f, h * 0.24f, ColorRole.TEXT, Theme.thin, Paint.Align.LEFT)
        text(c, Clock.date(d, "EEEE d MMMM").lowercase(), w * 0.085f, h * 0.74f, h * 0.05f, ColorRole.TEXT_2, Theme.light, Paint.Align.LEFT)
        p.color = Look.c(ColorRole.ACCENT)
        c.drawRect(w * 0.085f, h * 0.31f, w * 0.085f + h * 0.08f, h * 0.31f + h * 0.008f, p)
    }

    private fun analog(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cal = Calendar.getInstance()
        val ms = cal.get(Calendar.MILLISECOND)
        val sec = cal.get(Calendar.SECOND) + ms / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) + min / 60f)
        val cx = w * 0.36f; val cy = h / 2; val rad = h * 0.42f

        p.style = Paint.Style.FILL; p.color = Look.c(ColorRole.TILE)
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Look.c(ColorRole.TILE_BORDER)
        c.drawCircle(cx, cy, rad, p)
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            p.strokeWidth = if (major) rad * 0.025f else rad * 0.01f
            p.strokeCap = Paint.Cap.ROUND
            p.color = Look.c(if (major) ColorRole.TEXT else ColorRole.TEXT_2)
            val r1 = rad * (if (major) 0.84f else 0.89f); val r2 = rad * 0.93f
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p)
        }
        fun hand(angleDeg: Float, len: Float, width: Float, role: ColorRole) {
            val a = Math.toRadians(angleDeg - 90.0)
            p.strokeWidth = width; p.color = Look.c(role); p.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx - (cos(a) * len * 0.12f).toFloat(), cy - (sin(a) * len * 0.12f).toFloat(),
                cx + (cos(a) * len).toFloat(), cy + (sin(a) * len).toFloat(), p)
        }
        hand(hr * 30f, rad * 0.5f, rad * 0.05f, ColorRole.TEXT)
        hand(min * 6f, rad * 0.75f, rad * 0.035f, ColorRole.TEXT)
        hand(sec * 6f, rad * 0.82f, rad * 0.012f, ColorRole.CLOCK_HANDS)
        p.style = Paint.Style.FILL; p.color = Look.c(ColorRole.CLOCK_HANDS)
        c.drawCircle(cx, cy, rad * 0.035f, p)

        val tx = w * 0.79f
        val d = Date()
        text(c, Clock.date(d, "EEE").uppercase(), tx, h * 0.38f, h * 0.06f, ColorRole.WARNING, Theme.medium)
        text(c, Clock.date(d, "d"), tx, h * 0.6f, h * 0.2f, ColorRole.TEXT, Theme.light)
        text(c, Clock.date(d, "MMMM"), tx, h * 0.7f, h * 0.05f, ColorRole.TEXT_2, Theme.regular)
    }

    private fun flip(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = Date()
        val t = Clock.time(context, d).padStart(5, '0').replace(":", "")
        val cardW = min(w * 0.2f, h * 0.48f); val cardH = cardW * 1.45f
        val gap = cardW * 0.08f; val colon = cardW * 0.32f
        val total = cardW * 4 + gap * 2 + colon
        var x = (w - total) / 2
        val top = (h - cardH) / 2 - h * 0.03f
        t.forEachIndexed { i, ch ->
            r.set(x, top, x + cardW, top + cardH)
            p.style = Paint.Style.FILL; p.color = Look.c(ColorRole.TILE)
            c.drawRoundRect(r, cardW * 0.1f, cardW * 0.1f, p)
            text(c, ch.toString(), r.centerX(), r.centerY() + cardH * 0.24f, cardH * 0.68f, ColorRole.CLOCK, Theme.medium)
            p.color = Look.c(ColorRole.BACKGROUND)
            c.drawRect(r.left, r.centerY() - cardH * 0.008f, r.right, r.centerY() + cardH * 0.008f, p)
            x += cardW + if (i == 1) colon + gap else gap
            if (i == 1) {
                p.color = Look.c(ColorRole.TEXT_2)
                val cxp = x - colon / 2 - gap
                c.drawCircle(cxp, top + cardH * 0.36f, cardW * 0.05f, p)
                c.drawCircle(cxp, top + cardH * 0.64f, cardW * 0.05f, p)
            }
        }
        text(c, listOf(Clock.amPm(context, d), Clock.date(d)).filter { it.isNotEmpty() }.joinToString("  ·  "),
            w / 2, top + cardH + h * 0.1f, h * 0.05f, ColorRole.TEXT_2, Theme.regular)
    }

    /** Time framed by two tiny NØVA eyes that track the second hand. */
    private fun nova(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = Date()
        val time = Clock.time(context, d)
        val size = fit(time, w * 0.62f, h * 0.42f, Theme.light)
        text(c, time, w / 2, h * 0.56f + size * 0.3f, size, ColorRole.CLOCK, Theme.light)
        val sec = (System.currentTimeMillis() % 60_000) / 60_000f
        val look = sin(sec * Math.PI * 2).toFloat()
        val ew = h * 0.06f; val eh = h * 0.13f
        val blink = if (SystemClock.uptimeMillis() % 5_000 < 140) 0.15f else 1f
        for (dx in floatArrayOf(-1f, 1f)) {
            val ex = w / 2 + dx * h * 0.09f + look * h * 0.02f
            val ey = h * 0.2f
            p.style = Paint.Style.FILL; p.color = Theme.withAlpha(Look.c(ColorRole.GLOW), 40)
            r.set(ex - ew * 0.8f, ey - eh * 0.6f * blink, ex + ew * 0.8f, ey + eh * 0.6f * blink); c.drawOval(r, p)
            p.color = Look.c(ColorRole.EYES)
            r.set(ex - ew / 2, ey - eh / 2 * blink, ex + ew / 2, ey + eh / 2 * blink); c.drawOval(r, p)
        }
        text(c, Clock.date(d) + Clock.amPm(context, d).let { if (it.isEmpty()) "" else "  ·  $it" }, w / 2, h * 0.9f, h * 0.05f, ColorRole.TEXT_2, Theme.regular)
    }

    private fun dateSeconds(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = Date()
        text(c, Clock.date(d, "EEEE").uppercase(), w / 2, h * 0.2f, h * 0.06f, ColorRole.ACCENT, Theme.medium)
        val time = Clock.time(context, d, seconds = true)
        val size = fit(time, w * 0.9f, h * 0.36f, Theme.light)
        text(c, time, w / 2, h * 0.52f + size * 0.3f, size, ColorRole.CLOCK, Theme.light)
        text(c, Clock.date(d, "d MMMM yyyy") + Clock.amPm(context, d).let { if (it.isEmpty()) "" else "  ·  $it" },
            w / 2, h * 0.86f, h * 0.06f, ColorRole.TEXT_2, Theme.regular)
    }
}
