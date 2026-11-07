package com.nova.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import kotlin.math.min
import kotlin.math.sin

/**
 * Assistant View (spec v1.1 §14.3): large microphone button with a clear listening state and
 * on-screen mic indicator, live transcript, response, action status, confirmation buttons for
 * consequential actions, recent turns, and a "Type" button as the non-voice alternative.
 */
class AssistantView(context: Context, private val assistant: Assistant) : View(context) {

    var onType: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val mic = RectF(); private val typeBtn = RectF(); private val yesBtn = RectF(); private val noBtn = RectF()
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, if (assistant.state != Assistant.State.IDLE || Look.transitioning) 40L else 1_000L)
        }
    }

    init { contentDescription = "Assistant. Tap the microphone to talk." }

    fun resume() { if (!running) { running = true; post(tick) } }
    fun pause() { running = false; removeCallbacks(tick) }
    fun poke() { removeCallbacks(tick); if (running) post(tick) }

    override fun onDraw(c: Canvas) {
        c.drawColor(Look.c(ColorRole.BACKGROUND))
        val w = width.toFloat(); val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val st = assistant.state

        // Microphone button.
        val cx = w * 0.2f; val cy = h * 0.48f; val r = h * 0.17f
        val listening = st == Assistant.State.LISTENING
        if (listening) {
            val lvl = ((assistant.level + 2f) / 12f).coerceIn(0f, 1f)
            p.color = Theme.withAlpha(Look.c(ColorRole.ACCENT), 50)
            c.drawCircle(cx, cy, r * (1.25f + 0.35f * lvl), p)
            p.color = Theme.withAlpha(Look.c(ColorRole.ACCENT), 90)
            c.drawCircle(cx, cy, r * (1.1f + 0.15f * sin(now / 180.0).toFloat()), p)
        }
        if (st == Assistant.State.THINKING) {
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f; p.strokeCap = Paint.Cap.ROUND; p.color = Look.c(ColorRole.ACCENT_2)
            val a = (now / 3 % 360).toFloat()
            c.drawArc(RectF(cx - r * 1.2f, cy - r * 1.2f, cx + r * 1.2f, cy + r * 1.2f), a, 100f, false, p)
            p.style = Paint.Style.FILL
        }
        p.color = Look.c(if (listening) ColorRole.ACCENT else ColorRole.TILE)
        c.drawCircle(cx, cy, r, p)
        drawMicGlyph(c, cx, cy, r * 0.5f, Look.c(if (listening) ColorRole.BACKGROUND else ColorRole.ACCENT))
        mic.set(cx - r * 1.3f, cy - r * 1.3f, cx + r * 1.3f, cy + r * 1.3f)
        tp.typeface = Theme.medium; tp.textAlign = Paint.Align.CENTER; tp.textSize = h * 0.04f; tp.color = Look.c(ColorRole.TEXT_2)
        c.drawText(when (st) { Assistant.State.LISTENING -> "Tap to stop"; Assistant.State.THINKING -> "Thinking…"; else -> "Tap to talk" }, cx, cy + r * 1.75f, tp)

        // Type button (alternative to voice).
        typeBtn.set(cx - r * 0.9f, h * 0.86f, cx + r * 0.9f, h * 0.94f)
        p.color = Look.c(ColorRole.TILE); c.drawRoundRect(typeBtn, typeBtn.height() / 2, typeBtn.height() / 2, p)
        tp.color = Look.c(ColorRole.ACCENT); tp.textSize = h * 0.036f
        c.drawText("⌨  Type instead", typeBtn.centerX(), typeBtn.centerY() + h * 0.013f, tp)

        // Mic-on indicator (privacy: always visible while the microphone is open).
        if (listening) {
            p.color = Look.c(ColorRole.WARNING); c.drawCircle(w * 0.04f, h * 0.06f, h * 0.014f, p)
            tp.textAlign = Paint.Align.LEFT; tp.color = Look.c(ColorRole.WARNING); tp.textSize = h * 0.032f
            c.drawText("Microphone on", w * 0.06f, h * 0.072f, tp)
        }

        // Transcript + response column.
        val left = w * 0.4f; val colW = w * 0.56f
        var y = h * 0.1f
        tp.textAlign = Paint.Align.LEFT
        val heard = if (listening || st == Assistant.State.THINKING) assistant.partial else ""
        if (heard.isNotBlank()) {
            y = block(c, "“$heard”", left, y, colW, h * 0.055f, ColorRole.TEXT, Theme.light) + h * 0.02f
        }
        if (st == Assistant.State.CONFIRM) {
            y = block(c, assistant.pendingText, left, y, colW, h * 0.05f, ColorRole.TEXT, Theme.medium) + h * 0.03f
            yesBtn.set(left, y, left + colW * 0.45f, y + h * 0.1f)
            noBtn.set(left + colW * 0.52f, y, left + colW * 0.97f, y + h * 0.1f)
            p.color = Look.c(ColorRole.ACCENT); c.drawRoundRect(yesBtn, h * 0.05f, h * 0.05f, p)
            p.color = Look.c(ColorRole.TILE); c.drawRoundRect(noBtn, h * 0.05f, h * 0.05f, p)
            tp.textAlign = Paint.Align.CENTER; tp.textSize = h * 0.042f; tp.typeface = Theme.medium
            tp.color = Theme.onColor(Look.c(ColorRole.ACCENT)); c.drawText("Yes, do it", yesBtn.centerX(), yesBtn.centerY() + h * 0.015f, tp)
            tp.color = Look.c(ColorRole.TEXT); c.drawText("Cancel", noBtn.centerX(), noBtn.centerY() + h * 0.015f, tp)
            tp.textAlign = Paint.Align.LEFT
            y = yesBtn.bottom + h * 0.04f
        } else { yesBtn.setEmpty(); noBtn.setEmpty() }

        // Status line.
        y = block(c, assistant.status, left, y, colW, h * 0.04f, ColorRole.ACCENT, Theme.regular) + h * 0.04f

        // Recent turns.
        for (turn in assistant.history) {
            if (y > h * 0.9f) break
            y = block(c, "You: ${turn.you}", left, y, colW, h * 0.034f, ColorRole.TEXT_2, Theme.regular) + h * 0.005f
            y = block(c, "NØVA: ${turn.nova}", left, y, colW, h * 0.034f, ColorRole.TEXT, Theme.regular) + h * 0.025f
        }
    }

    private fun block(c: Canvas, s: String, x: Float, y: Float, w: Float, size: Float, role: ColorRole, face: android.graphics.Typeface): Float {
        if (s.isBlank()) return y
        tp.textSize = size; tp.typeface = face; tp.color = Look.c(role); tp.textAlign = Paint.Align.LEFT
        @Suppress("DEPRECATION")
        val layout = StaticLayout(s, tp, w.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
        val lines = min(layout.lineCount, 4)
        c.save(); c.translate(x, y); c.clipRect(0f, 0f, w, layout.getLineBottom(lines - 1).toFloat()); layout.draw(c); c.restore()
        return y + layout.getLineBottom(lines - 1)
    }

    private fun drawMicGlyph(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        p.color = color
        c.drawRoundRect(RectF(cx - s * 0.32f, cy - s * 0.8f, cx + s * 0.32f, cy + s * 0.25f), s * 0.32f, s * 0.32f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.12f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - s * 0.55f, cy - s * 0.45f, cx + s * 0.55f, cy + s * 0.5f), 0f, 180f, false, p)
        c.drawLine(cx, cy + s * 0.5f, cx, cy + s * 0.8f, p)
        p.style = Paint.Style.FILL
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when {
                yesBtn.contains(e.x, e.y) -> assistant.confirm(true)
                noBtn.contains(e.x, e.y) -> assistant.confirm(false)
                typeBtn.contains(e.x, e.y) -> onType?.invoke()
                mic.contains(e.x, e.y) -> assistant.listen()
                else -> return false
            }
            poke(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
}
