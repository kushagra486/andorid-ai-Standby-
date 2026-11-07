package com.nova.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nova.audio.SoundFx
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The NØVA face: black rounded faceplate, two glowing cyan oval eyes and a short cyan
 * pill mouth. Drawn with plain Canvas calls (no bitmaps, no blur filters) so it stays
 * cheap on the ZenFone's MT6737T. The frame loop runs only while the view is resumed.
 */
class PetView(context: Context, private val sound: SoundFx) : View(context) {

    interface Listener {
        fun onLongPress()
    }

    var listener: Listener? = null

    // ---- Tunables -------------------------------------------------------------------
    private val frameMs = 33L                       // ~30 fps is plenty for this face
    private val sleepyAfterMs = 5 * 60_000L         // idle → sleepy
    private val sleepAfterMs = 15 * 60_000L         // idle → sleeping
    private val hintVisibleMs = 7_000L

    // ---- Colours / paints -------------------------------------------------------------
    private val cyan = Color.rgb(0x22, 0xE5, 0xFF)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x0B, 0x0F, 0x14) }
    private val faceEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0x16, 0x1E, 0x27)
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9A, 0xF6, 0xFF) }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = cyan
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; textAlign = Paint.Align.CENTER
    }

    // ---- Animated state -----------------------------------------------------------------
    private var expression = Expression.IDLE
    private var baseExpression = Expression.IDLE
    private var reactionUntil = 0L
    private var cur = FloatArray(12).also { write(it, Expression.IDLE.shape) }
    private val tgt = FloatArray(12)

    private var nextBlinkAt = 0L
    private var blinkStart = -1L
    private val blinkMs = 150L

    private var lookX = 0f; private var lookY = 0f
    private var lookTX = 0f; private var lookTY = 0f
    private var nextLookAt = 0L

    private var hopStart = -1L
    private var lastInteraction = SystemClock.uptimeMillis()
    private var startedAt = SystemClock.uptimeMillis()
    private val recentTaps = ArrayDeque<Long>()

    private var running = false
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, frameMs)
        }
    }

    private val rect = RectF()
    private val path = Path()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(e.x); return true }
        override fun onLongPress(e: MotionEvent) {
            touched()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sound.play(SoundFx.Sound.CHIRP)
            listener?.onLongPress()
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        isHapticFeedbackEnabled = true
    }

    // ---- Lifecycle ----------------------------------------------------------------------
    fun resume() {
        if (running) return
        running = true
        removeCallbacks(frame)
        post(frame)
    }

    fun pause() {
        running = false
        removeCallbacks(frame)
    }

    /** Show an expression for its hold time, then fall back to the idle/sleepy base. */
    fun react(e: Expression) {
        expression = e
        reactionUntil = SystemClock.uptimeMillis() + e.holdMs
        hopStart = SystemClock.uptimeMillis()
    }

    /** Called by the activity whenever the user interacts with any NØVA screen. */
    fun touched() {
        lastInteraction = SystemClock.uptimeMillis()
        if (baseExpression != Expression.IDLE) {
            val wasSleeping = baseExpression == Expression.SLEEPING
            baseExpression = Expression.IDLE
            if (wasSleeping) {
                react(Expression.SURPRISED)
                sound.play(SoundFx.Sound.DOUBLE_CHIRP)
            }
        }
    }

    // ---- Input --------------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    private fun onTap(x: Float) {
        val wasAsleep = baseExpression != Expression.IDLE
        touched()
        if (wasAsleep) return          // touched() already played the wake-up reaction
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        val now = SystemClock.uptimeMillis()
        recentTaps.addLast(now)
        while (recentTaps.isNotEmpty() && now - recentTaps.first() > 3_000) recentTaps.removeFirst()

        // Glance toward where we were poked.
        lookTX = ((x / width.coerceAtLeast(1)) - 0.5f) * 0.6f
        lookTY = 0f
        nextLookAt = now + 1_500

        if (recentTaps.size >= 7) {
            recentTaps.clear()
            react(Expression.ANNOYED)
            sound.play(SoundFx.Sound.HMM)
            return
        }
        val e = Expression.TAP_REACTIONS[Random.nextInt(Expression.TAP_REACTIONS.size)]
        react(e)
        sound.play(
            when (e) {
                Expression.HAPPY -> SoundFx.Sound.HAPPY
                Expression.SURPRISED -> SoundFx.Sound.DOUBLE_CHIRP
                Expression.CURIOUS -> SoundFx.Sound.HMM
                else -> SoundFx.Sound.CHIRP
            }
        )
    }

    // ---- Per-frame update ---------------------------------------------------------------
    private fun update(now: Long) {
        val idleFor = now - lastInteraction
        val newBase = when {
            idleFor > sleepAfterMs -> Expression.SLEEPING
            idleFor > sleepyAfterMs -> Expression.SLEEPY
            else -> Expression.IDLE
        }
        if (newBase == Expression.SLEEPING && baseExpression != Expression.SLEEPING) {
            sound.play(SoundFx.Sound.SLEEP)
        }
        baseExpression = newBase
        if (now > reactionUntil) expression = baseExpression

        write(tgt, expression.shape)
        for (i in cur.indices) cur[i] += (tgt[i] - cur[i]) * 0.22f

        // Blinking (not while asleep).
        if (nextBlinkAt == 0L) nextBlinkAt = now + 2_000
        if (expression != Expression.SLEEPING && blinkStart < 0 && now >= nextBlinkAt) {
            blinkStart = now
        }
        if (blinkStart >= 0 && now - blinkStart > blinkMs) {
            blinkStart = -1
            // Occasionally double-blink.
            nextBlinkAt = now + if (Random.nextInt(6) == 0) 220 else Random.nextLong(2_500, 6_500)
        }

        // Eye wandering.
        if (now >= nextLookAt) {
            val calm = expression == Expression.SLEEPY || expression == Expression.SLEEPING
            if (calm || Random.nextInt(3) == 0) { lookTX = 0f; lookTY = 0f } else {
                lookTX = Random.nextFloat() * 0.5f - 0.25f
                lookTY = Random.nextFloat() * 0.2f - 0.1f
            }
            nextLookAt = now + Random.nextLong(1_800, 5_000)
        }
        lookX += (lookTX - lookX) * 0.15f
        lookY += (lookTY - lookY) * 0.15f
    }

    private fun blinkFactor(now: Long): Float {
        if (blinkStart < 0) return 1f
        val t = (now - blinkStart).toFloat() / blinkMs
        return (abs(t * 2f - 1f)).coerceIn(0.05f, 1f)
    }

    // ---- Drawing ------------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        update(now)

        val w = width.toFloat(); val h = height.toFloat()
        val faceW = min(w * 0.86f, h * 1.55f)
        val faceH = faceW * 0.58f

        // Gentle "breathing" plus a little hop on reactions.
        val breathe = sin(now / 900.0).toFloat() * faceH * 0.008f
        var hop = 0f
        if (hopStart >= 0) {
            val t = (now - hopStart) / 320f
            if (t >= 1f) hopStart = -1 else hop = -sin(t * Math.PI).toFloat() * faceH * 0.035f
        }
        val cx = w / 2f; val cy = h / 2f + breathe + hop

        // Faceplate.
        rect.set(cx - faceW / 2, cy - faceH / 2, cx + faceW / 2, cy + faceH / 2)
        val r = faceH * 0.22f
        canvas.drawRoundRect(rect, r, r, facePaint)
        faceEdgePaint.strokeWidth = faceH * 0.012f
        canvas.drawRoundRect(rect, r, r, faceEdgePaint)

        val glow = cur[G_GLOW]
        val blink = blinkFactor(now)
        val eyeW = faceW * 0.12f * cur[EYE_W]
        val eyeH = faceH * 0.40f
        val eyeDX = faceW * 0.19f
        val eyeY = cy - faceH * 0.06f
        val lookPxX = lookX * faceW * 0.12f
        val lookPxY = lookY * faceH * 0.2f

        drawEye(canvas, cx - eyeDX + lookPxX, eyeY + lookPxY + cur[L_LIFT] * eyeH,
            eyeW, eyeH * cur[L_OPEN] * blink, cur[L_TILT], glow)
        drawEye(canvas, cx + eyeDX + lookPxX, eyeY + lookPxY + cur[R_LIFT] * eyeH,
            eyeW, eyeH * cur[R_OPEN] * blink, -cur[R_TILT], glow)

        // Mouth: a quadratic curve stroked with round caps (straight = pill).
        val mw = faceW * 0.09f * cur[M_W]
        val mt = faceH * 0.045f * cur[M_T]
        val my = cy + faceH * 0.27f + lookPxY * 0.4f
        val mx = cx + lookPxX * 0.5f
        val skew = cur[M_SKEW] * mw
        path.reset()
        path.moveTo(mx - mw / 2, my + skew / 2)
        path.quadTo(mx, my + cur[M_CURVE] * mw * 2f, mx + mw / 2, my - skew / 2)
        mouthPaint.color = cyan
        mouthPaint.alpha = (40 * glow).toInt().coerceIn(0, 255)
        mouthPaint.strokeWidth = mt * 2.2f
        canvas.drawPath(path, mouthPaint)
        mouthPaint.alpha = (255 * min(1f, 0.55f + glow * 0.45f)).toInt()
        mouthPaint.strokeWidth = mt
        canvas.drawPath(path, mouthPaint)

        if (expression == Expression.SLEEPING) drawZs(canvas, now, cx + faceW * 0.36f, cy - faceH * 0.3f, faceH)
        drawHint(canvas, now, cx, h - h * 0.06f, h)
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, w: Float, rawH: Float, tilt: Float, glow: Float) {
        val h = rawH.coerceAtLeast(w * 0.18f)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(tilt)
        // Soft glow: a few translucent, slightly larger ellipses (much cheaper than blur).
        for (i in 3 downTo 1) {
            val g = w * 0.10f * i
            glowPaint.alpha = (18 * glow / i * 1.6f).toInt().coerceIn(0, 255)
            rect.set(-w / 2 - g, -h / 2 - g, w / 2 + g, h / 2 + g)
            canvas.drawOval(rect, glowPaint)
        }
        eyePaint.alpha = (255 * min(1f, 0.55f + glow * 0.45f)).toInt()
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        canvas.drawOval(rect, eyePaint)
        // Restrained inner highlight.
        if (h > w * 0.6f) {
            innerPaint.alpha = (110 * min(1f, glow)).toInt()
            rect.set(-w * 0.28f, -h * 0.36f, w * 0.02f, -h * 0.08f)
            canvas.drawOval(rect, innerPaint)
        }
        canvas.restore()
    }

    private fun drawZs(canvas: Canvas, now: Long, x: Float, y: Float, faceH: Float) {
        for (i in 0 until 3) {
            val t = ((now / 2600f) + i / 3f) % 1f
            textPaint.textSize = faceH * (0.07f + 0.05f * t)
            textPaint.alpha = (200 * sin(t * Math.PI)).toInt().coerceIn(0, 255)
            canvas.drawText("z", x + t * faceH * 0.15f, y - t * faceH * 0.3f, textPaint)
        }
    }

    private fun drawHint(canvas: Canvas, now: Long, x: Float, y: Float, h: Float) {
        val age = now - startedAt
        if (age > hintVisibleMs) return
        val fade = ((hintVisibleMs - age) / 1_500f).coerceIn(0f, 1f)
        textPaint.textSize = h * 0.035f
        textPaint.alpha = (120 * fade).toInt()
        canvas.drawText("tap me  ·  long-press for clock", x, y, textPaint)
    }

    private companion object {
        const val L_OPEN = 0; const val R_OPEN = 1; const val EYE_W = 2
        const val L_TILT = 3; const val R_TILT = 4; const val L_LIFT = 5; const val R_LIFT = 6
        const val M_W = 7; const val M_T = 8; const val M_CURVE = 9; const val M_SKEW = 10
        const val G_GLOW = 11

        fun write(a: FloatArray, s: FaceShape) {
            a[L_OPEN] = s.leftOpen; a[R_OPEN] = s.rightOpen; a[EYE_W] = s.eyeWidth
            a[L_TILT] = s.leftTilt; a[R_TILT] = s.rightTilt
            a[L_LIFT] = s.leftLift; a[R_LIFT] = s.rightLift
            a[M_W] = s.mouthWidth; a[M_T] = s.mouthThickness
            a[M_CURVE] = s.mouthCurve; a[M_SKEW] = s.mouthSkew; a[G_GLOW] = s.glow
        }
    }
}
