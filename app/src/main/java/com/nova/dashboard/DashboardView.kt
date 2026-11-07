package com.nova.dashboard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.nova.settings.NightStyle
import com.nova.settings.NovaSettings
import com.nova.ui.Theme
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

/**
 * Clock page in the spirit of Apple's StandBy: side-by-side rounded widget cards on true
 * black — a large clock, a battery ring and a NØVA card that opens Settings. At night it
 * dims or turns deep red like StandBy's night mode.
 * More widgets (weather, Wi-Fi/Bluetooth, calendar, Spotify) arrive in Stage 3.
 */
class DashboardView(context: Context) : View(context) {

    interface Listener {
        fun onDismiss()
        fun onOpenSettings()
    }

    var listener: Listener? = null
    private var settings = NovaSettings()
    private var night = 0f
    private var nightTarget = 0f
    private var lastNightCheck = 0L

    fun apply(s: NovaSettings) {
        settings = s
        lastNightCheck = 0L
        invalidate()
    }

    private val green = Color.rgb(0x30, 0xD1, 0x58)
    private val red = Color.rgb(0xFF, 0x45, 0x3A)

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.light; textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.medium; textAlign = Paint.Align.CENTER }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.regular; textAlign = Paint.Align.CENTER }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clockCard = RectF()
    private val batteryCard = RectF()
    private val novaCard = RectF()
    private val tmp = RectF()

    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            // Smooth night fade needs frames; otherwise wake just after each new second.
            val delay = if (abs(nightTarget - night) > 0.01f) 50L
            else 1_000 - System.currentTimeMillis() % 1_000 + 5
            postDelayed(this, delay)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (novaCard.contains(e.x, e.y)) listener?.onOpenSettings() else listener?.onDismiss()
            return true
        }
        override fun onLongPress(e: MotionEvent) { listener?.onDismiss() }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < 900 || abs(vx) < abs(vy) * 1.5f) return false
            listener?.onDismiss()
            return true
        }
    })

    init { setBackgroundColor(Color.BLACK) }

    fun resume() { if (!running) { running = true; post(tick) } }
    fun pause() { running = false; removeCallbacks(tick) }

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    private fun c(color: Int) = Theme.night(color, night, settings.nightStyle)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now - lastNightCheck > 30_000 || lastNightCheck == 0L) {
            nightTarget = if (settings.isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) 1f else 0f
            lastNightCheck = now
        }
        night += (nightTarget - night) * 0.12f
        if (abs(nightTarget - night) < 0.01f) night = nightTarget

        val w = width.toFloat(); val h = height.toFloat()
        val pad = h * 0.05f
        val gap = h * 0.035f
        val radius = h * 0.075f
        val bottom = h - pad * 1.6f          // leave room for the page dots

        // Layout: clock card on the left (~62%), two stacked cards on the right.
        val rightW = (w - pad * 2 - gap) * 0.38f
        clockCard.set(pad, pad, w - pad - gap - rightW, bottom)
        val rx = clockCard.right + gap
        val half = (bottom - pad - gap) / 2
        batteryCard.set(rx, pad, w - pad, pad + half)
        novaCard.set(rx, batteryCard.bottom + gap, w - pad, bottom)

        cardPaint.color = c(Theme.CARD)
        canvas.drawRoundRect(clockCard, radius, radius, cardPaint)
        canvas.drawRoundRect(batteryCard, radius, radius, cardPaint)
        canvas.drawRoundRect(novaCard, radius, radius, cardPaint)

        drawClock(canvas, clockCard)
        drawBattery(canvas, batteryCard)
        drawNova(canvas, novaCard)

        // Page dots: face · clock (StandBy-style paging hint).
        val dotY = h - pad * 0.8f
        val dr = h * 0.008f
        dotPaint.color = c(Theme.TERTIARY)
        canvas.drawCircle(w / 2 - dr * 2.5f, dotY, dr, dotPaint)
        dotPaint.color = c(Theme.LABEL)
        canvas.drawCircle(w / 2 + dr * 2.5f, dotY, dr, dotPaint)
    }

    private fun drawClock(canvas: Canvas, card: RectF) {
        val date = Date()
        val is24 = DateFormat.is24HourFormat(context)
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", date).toString()

        // Biggest light-weight numerals that fit the card.
        clockPaint.textSize = card.height() * 0.55f
        val maxW = card.width() * 0.88f
        val measured = clockPaint.measureText(time)
        if (measured > maxW) clockPaint.textSize *= maxW / measured
        clockPaint.color = c(Theme.CYAN)
        val baseline = card.centerY() + clockPaint.textSize * 0.30f
        canvas.drawText(time, card.centerX(), baseline, clockPaint)

        secondaryPaint.textSize = card.height() * 0.075f
        secondaryPaint.color = c(Theme.SECONDARY)
        val amPm = if (is24) "" else "  ·  " + DateFormat.format("a", date).toString().uppercase()
        val day = DateFormat.format("EEEE, d MMMM", date).toString()
        canvas.drawText(day + amPm, card.centerX(), card.bottom - card.height() * 0.11f, secondaryPaint)
    }

    private fun drawBattery(canvas: Canvas, card: RectF) {
        // Sticky broadcast: no permission and no receiver registration needed.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val size = min(card.height() * 0.62f, card.width() * 0.42f)
        val stroke = size * 0.11f
        val cx = card.left + card.width() * 0.30f
        val cy = card.centerY()
        tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        ringPaint.strokeWidth = stroke
        ringPaint.color = c(Theme.CARD_HIGHLIGHT)
        canvas.drawArc(tmp, 0f, 360f, false, ringPaint)
        if (pct > 0) {
            ringPaint.color = c(when { charging -> green; pct <= 20 -> red; else -> Theme.CYAN })
            canvas.drawArc(tmp, -90f, 360f * pct / 100f, false, ringPaint)
        }
        labelPaint.textSize = size * 0.30f
        labelPaint.color = c(Theme.LABEL)
        canvas.drawText(if (pct >= 0) "$pct" else "—", cx, cy + labelPaint.textSize * 0.35f, labelPaint)

        val tx = card.left + card.width() * 0.70f
        labelPaint.textSize = card.height() * 0.13f
        canvas.drawText("Battery", tx, cy - card.height() * 0.02f, labelPaint)
        secondaryPaint.textSize = card.height() * 0.11f
        secondaryPaint.color = c(Theme.SECONDARY)
        canvas.drawText(if (charging) "Charging" else if (pct >= 0) "$pct%" else "", tx, cy + card.height() * 0.15f, secondaryPaint)
    }

    private fun drawNova(canvas: Canvas, card: RectF) {
        // Tiny NØVA eyes as the card's "app icon".
        val eyeH = card.height() * 0.26f
        val eyeW = eyeH * 0.55f
        val ex = card.left + card.width() * 0.22f
        val ey = card.centerY()
        dotPaint.color = c(Theme.CYAN)
        for (dx in floatArrayOf(-eyeW * 0.85f, eyeW * 0.85f)) {
            tmp.set(ex + dx - eyeW / 2, ey - eyeH / 2, ex + dx + eyeW / 2, ey + eyeH / 2)
            canvas.drawOval(tmp, dotPaint)
        }
        val tx = card.left + card.width() * 0.64f
        labelPaint.textSize = card.height() * 0.13f
        labelPaint.color = c(Theme.LABEL)
        canvas.drawText("Settings", tx, ey - card.height() * 0.02f, labelPaint)
        secondaryPaint.textSize = card.height() * 0.10f
        secondaryPaint.color = c(Theme.SECONDARY)
        val sub = when {
            nightTarget > 0f -> if (settings.nightStyle == NightStyle.RED) "Night · red" else "Night · dim"
            !settings.soundOn -> "Sound off"
            else -> "Sound on"
        }
        canvas.drawText(sub, tx, ey + card.height() * 0.15f, secondaryPaint)
    }
}
