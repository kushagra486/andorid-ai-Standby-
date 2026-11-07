package com.nova.dashboard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Date

/**
 * Stage-1 dashboard: big clock, date, battery and a sound toggle.
 * Weather, Wi-Fi/Bluetooth, calendar and Spotify widgets arrive in Stage 3.
 */
class DashboardView(context: Context) : View(context) {

    interface Listener {
        fun onDismiss()
        fun onToggleSound(): Boolean   // returns new muted state
    }

    var listener: Listener? = null
    var muted = false

    private val cyan = Color.rgb(0x22, 0xE5, 0xFF)
    private val dim = Color.rgb(0x5C, 0x7A, 0x85)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textAlign = Paint.Align.CENTER }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; textAlign = Paint.Align.CENTER }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x0B, 0x0F, 0x14) }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textAlign = Paint.Align.CENTER }
    private val soundChip = RectF()

    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            // Wake up right after the next whole second so the clock never lags.
            postDelayed(this, 1_000 - System.currentTimeMillis() % 1_000 + 5)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (soundChip.contains(e.x, e.y)) {
                muted = listener?.onToggleSound() ?: muted
                invalidate()
            } else {
                listener?.onDismiss()
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) { listener?.onDismiss() }
    })

    init { setBackgroundColor(Color.BLACK) }

    fun resume() { if (!running) { running = true; post(tick) } }
    fun pause() { running = false; removeCallbacks(tick) }

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = Date()
        val is24 = DateFormat.is24HourFormat(context)

        timePaint.textSize = h * 0.30f
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
        canvas.drawText(time, w / 2, h * 0.47f, timePaint)

        subPaint.textSize = h * 0.065f
        val date = DateFormat.format("EEEE, d MMMM", now).toString()
        val secs = DateFormat.format("ss", now).toString()
        val amPm = if (is24) "" else "  " + DateFormat.format("a", now)
        canvas.drawText("$date$amPm  ·  :$secs", w / 2, h * 0.62f, subPaint)

        // Battery (sticky broadcast — no permission and no receiver registration needed).
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1

        chipText.textSize = h * 0.05f
        val chipH = h * 0.11f
        val chipY = h * 0.80f
        val batteryLabel = if (pct >= 0) "battery $pct%" + (if (charging) " ⚡" else "") else "battery —"
        drawChip(canvas, batteryLabel, w * 0.35f, chipY, chipH, null)
        drawChip(canvas, if (muted) "sound off" else "sound on", w * 0.65f, chipY, chipH, soundChip)

        subPaint.textSize = h * 0.035f
        canvas.drawText("tap anywhere to return to NØVA", w / 2, h * 0.95f, subPaint)
    }

    private fun drawChip(canvas: Canvas, label: String, cx: Float, cy: Float, chipH: Float, out: RectF?) {
        val tw = chipText.measureText(label) + chipH
        val r = RectF(cx - tw / 2, cy - chipH / 2, cx + tw / 2, cy + chipH / 2)
        canvas.drawRoundRect(r, chipH / 2, chipH / 2, chipPaint)
        canvas.drawText(label, cx, cy - (chipText.ascent() + chipText.descent()) / 2, chipText)
        out?.set(r)
    }
}
