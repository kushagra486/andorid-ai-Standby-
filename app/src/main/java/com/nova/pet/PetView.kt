package com.nova.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nova.audio.SoundFx
import com.nova.audio.SoundFx.Sound
import com.nova.storage.Intensity
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The NØVA face: rounded faceplate, two glowing oval eyes and a short pill mouth, drawn with
 * plain Canvas calls (no bitmaps, no blur) so it stays cheap on the ZenFone's MT6737T.
 *
 * Personality lives in [PetBrain]; this view renders it. Face geometry follows the brain's
 * expression through damped springs (gentle overshoot). Colours come from [Look], so themes,
 * custom colours and night palettes apply automatically. Frame rate adapts to what's shown.
 */
class PetView(context: Context, private val sound: SoundFx) : View(context) {

    interface Listener {
        fun onPetLongPress()
        fun onExpressionTestFinished()
    }

    var listener: Listener? = null

    val brain = PetBrain(cue = { e -> soundFor(e)?.let { sound.play(it, now()) } })

    /** Optional single line shown above the face (time, status or music), or null. */
    var compactLine: (() -> String?)? = null

    /** Scale the face down (e.g. when sharing the screen with music info). */
    var faceScale = 1f

    private var intensity = Intensity.NORMAL
    fun applyPrefs() {
        intensity = Prefs.intensity
        brain.spontaneous = intensity != Intensity.LOW
    }

    // ---- Paints -------------------------------------------------------------------------
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Theme.light }

    // ---- Spring-animated geometry -------------------------------------------------------
    private val pos = FloatArray(N).also { write(it, Expression.IDLE.shape) }
    private val vel = FloatArray(N)
    private val tgt = FloatArray(N)
    private val stiffness = 170f
    private val damping = 2f * 0.72f * sqrt(170f)   // under-damped: a gentle overshoot

    // ---- Small motions ------------------------------------------------------------------
    private var nextBlinkAt = 0L
    private var blinkStart = -1L
    private val blinkMs = 150L
    private var lookX = 0f; private var lookY = 0f
    private var lookTX = 0f; private var lookTY = 0f
    private var nextLookAt = 0L
    private var glitchX = 0f
    private var nextGlitchAt = 0L
    private var lastFrame = 0L
    private var startedAt = 0L
    private var started = false

    // ---- Expression test ----------------------------------------------------------------
    private var testIndex = -1
    private var nextTestAt = 0L
    private val testStepMs = 2_600L
    val testing: Boolean get() = testIndex >= 0

    private var running = false
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, frameDelay())
        }
    }

    private val rect = RectF()
    private val path = Path()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(e.x); return true }
        override fun onLongPress(e: MotionEvent) {
            if (testIndex >= 0) return
            brain.touch(now())
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sound.play(Sound.CHIRP, now())
            listener?.onPetLongPress()
        }
    })

    init {
        isHapticFeedbackEnabled = true
        contentDescription = "NØVA. Tap to play, long-press for the quick menu."
        applyPrefs()
    }

    private fun now() = SystemClock.uptimeMillis()

    // ---- Lifecycle ----------------------------------------------------------------------
    fun resume() {
        if (running) return
        running = true
        lastFrame = 0L
        if (!started) {
            started = true
            startedAt = now()
            brain.start(now())
        }
        removeCallbacks(frame)
        post(frame)
    }

    fun pause() {
        running = false
        removeCallbacks(frame)
    }

    /** Wake/reset the idle timer from elsewhere. */
    fun touched() = brain.touch(now())

    /** Steps through every expression with its sound, for on-device acceptance tests. */
    fun startExpressionTest() {
        testIndex = 0
        nextTestAt = now()
        brain.touch(now())
    }

    private fun stopExpressionTest() {
        testIndex = -1
        brain.forced = null
        listener?.onExpressionTestFinished()
    }

    // ---- Input --------------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    private fun onTap(x: Float) {
        if (testIndex >= 0) { stopExpressionTest(); return }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        brain.tap(now())
        lookTX = ((x / width.coerceAtLeast(1)) - 0.5f) * 0.6f   // glance toward the poke
        lookTY = 0f
        nextLookAt = now() + 1_500
    }

    fun soundFor(e: Expression): Sound? = when (e) {
        Expression.HAPPY -> Sound.HAPPY
        Expression.SURPRISED -> Sound.DOUBLE_CHIRP
        Expression.CURIOUS -> Sound.CURIOUS
        Expression.MISCHIEVOUS -> Sound.CHIRP
        Expression.CONFUSED -> Sound.HMM
        Expression.ANNOYED -> Sound.GLITCH
        Expression.THINKING -> Sound.THINK
        Expression.LISTENING -> Sound.LISTEN
        Expression.ERROR -> Sound.ERROR
        Expression.SLEEPING -> Sound.SLEEP
        else -> null
    }

    // ---- Per-frame update ---------------------------------------------------------------
    private fun frameDelay(): Long {
        val e = brain.current
        return when {
            testIndex >= 0 || e.motion != Motion.NONE || e != brain.base || Look.transitioning -> 33L
            e == Expression.SLEEPING -> 125L
            e == Expression.SLEEPY || intensity == Intensity.LOW -> 66L
            else -> 40L
        }
    }

    private fun update(now: Long) {
        val dt = if (lastFrame == 0L) 0.033f else ((now - lastFrame) / 1000f).coerceAtMost(0.25f)
        lastFrame = now

        brain.nightMode = Look.isNight
        sound.nightMuted = Look.isNight

        if (testIndex >= 0 && now >= nextTestAt) {
            val all = Expression.entries
            if (testIndex >= all.size) stopExpressionTest() else {
                val e = all[testIndex]
                brain.forced = e
                soundFor(e)?.let { sound.play(it, now, force = true) }
                testIndex++
                nextTestAt = now + testStepMs
            }
        }

        brain.tick(now)
        val e = brain.current
        write(tgt, e.shape)

        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 0.02f)
            for (i in 0 until N) {
                val a = (tgt[i] - pos[i]) * stiffness - vel[i] * damping
                vel[i] += a * h
                pos[i] += vel[i] * h
            }
            remaining -= h
        }

        if (nextBlinkAt == 0L) nextBlinkAt = now + 2_000
        if (e != Expression.SLEEPING && blinkStart < 0 && now >= nextBlinkAt) blinkStart = now
        if (blinkStart >= 0 && now - blinkStart > blinkMs) {
            blinkStart = -1
            nextBlinkAt = now + if (Random.nextInt(6) == 0) 220 else Random.nextLong(2_500, 6_500)
        }

        when {
            e.motion == Motion.SCAN -> {
                lookTX = sin(now / 650.0).toFloat() * 0.28f
                lookTY = -0.18f
            }
            now >= nextLookAt -> {
                val calm = e == Expression.SLEEPY || e == Expression.SLEEPING || intensity == Intensity.LOW
                if (calm || Random.nextInt(3) == 0) { lookTX = 0f; lookTY = 0f } else {
                    val range = if (intensity == Intensity.HIGH) 0.32f else 0.25f
                    lookTX = Random.nextFloat() * range * 2 - range
                    lookTY = Random.nextFloat() * 0.2f - 0.1f
                }
                nextLookAt = now + if (intensity == Intensity.HIGH) Random.nextLong(1_200, 3_500)
                else Random.nextLong(1_800, 5_000)
            }
        }
        val follow = min(1f, dt * 4.5f)
        lookX += (lookTX - lookX) * follow
        lookY += (lookTY - lookY) * follow

        if (e.motion == Motion.GLITCH) {
            if (now >= nextGlitchAt) {
                glitchX = if (Random.nextInt(3) == 0) Random.nextFloat() * 2 - 1 else 0f
                nextGlitchAt = now + Random.nextLong(60, 160)
            }
        } else glitchX = 0f
    }

    private fun blinkFactor(now: Long): Float {
        if (blinkStart < 0) return 1f
        val t = (now - blinkStart).toFloat() / blinkMs
        return (abs(t * 2f - 1f)).coerceIn(0.05f, 1f)
    }

    // ---- Drawing ------------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        val now = now()
        update(now)
        val e = brain.current
        val lively = intensity != Intensity.LOW

        canvas.drawColor(Look.c(ColorRole.BACKGROUND))
        val w = width.toFloat(); val h = height.toFloat()
        val compact = compactLine?.invoke()
        val faceW = min(w * 0.86f, h * 1.55f) * faceScale * (if (compact != null) 0.9f else 1f)
        val faceH = faceW * 0.58f

        var dy = if (lively) sin(now / 900.0).toFloat() * faceH * 0.008f else 0f
        val hopAge = now - brain.reactionStartedAt
        if (lively && brain.reactionStartedAt >= 0 && hopAge in 0..320) {
            dy -= sin(hopAge / 320.0 * Math.PI).toFloat() * faceH * 0.035f
        }
        var sway = 0f
        var pulse = 0f
        when (e.motion) {
            Motion.DANCE -> {
                val beat = now / 500.0 * Math.PI                     // 120 bpm
                dy -= abs(sin(beat)).toFloat() * faceH * (if (lively) 0.045f else 0.02f)
                sway = sin(beat / 2).toFloat() * (if (lively) 4f else 1.5f)
            }
            Motion.PULSE -> pulse = sin(now / 140.0).toFloat()
            else -> Unit
        }
        val cx = w / 2f + glitchX * faceW * 0.012f
        val cy = h / 2f + dy + (if (compact != null) h * 0.05f else 0f)

        val alert = pos[ALERT].coerceIn(0f, 1f)
        val warn = Look.c(ColorRole.WARNING)
        val eyeColor = Theme.lerpColor(Look.c(ColorRole.EYES), warn, alert)
        val mouthColor = Theme.lerpColor(Look.c(ColorRole.MOUTH), warn, alert)
        val glowColor = Theme.lerpColor(Look.c(ColorRole.GLOW), warn, alert)
        val dim = 1f - 0.35f * Look.night
        val glow = (pos[GLOW] + pulse * 0.25f) * dim
        val flicker = if (e.motion == Motion.GLITCH && glitchX != 0f) 0.6f else 1f

        canvas.save()
        if (sway != 0f) canvas.rotate(sway, cx, cy)

        rect.set(cx - faceW / 2, cy - faceH / 2, cx + faceW / 2, cy + faceH / 2)
        val r = faceH * 0.22f
        facePaint.color = Look.c(ColorRole.FACEPLATE)
        canvas.drawRoundRect(rect, r, r, facePaint)
        faceEdgePaint.color = Look.c(ColorRole.TILE_BORDER)
        faceEdgePaint.strokeWidth = faceH * 0.012f
        canvas.drawRoundRect(rect, r, r, faceEdgePaint)

        val blink = blinkFactor(now)
        val eyeW = faceW * 0.12f * pos[EYE_W] * (1f + pulse * 0.04f)
        val eyeH = faceH * 0.40f * (1f + pulse * 0.05f)
        val eyeDX = faceW * 0.19f
        val eyeY = cy - faceH * 0.06f
        val lookPxX = lookX * faceW * 0.12f
        val lookPxY = lookY * faceH * 0.2f

        eyePaint.color = eyeColor; glowPaint.color = glowColor; mouthPaint.color = mouthColor
        innerPaint.color = Theme.lerpColor(eyeColor, android.graphics.Color.WHITE, 0.55f)

        drawEye(canvas, cx - eyeDX + lookPxX, eyeY + lookPxY + pos[L_LIFT] * eyeH,
            eyeW, eyeH * pos[L_OPEN] * blink, pos[L_TILT], glow, flicker)
        drawEye(canvas, cx + eyeDX + lookPxX, eyeY + lookPxY + pos[R_LIFT] * eyeH,
            eyeW, eyeH * pos[R_OPEN] * blink, -pos[R_TILT], glow, flicker)

        val mw = faceW * 0.09f * pos[M_W].coerceAtLeast(0.1f)
        val mt = faceH * 0.045f * pos[M_T].coerceAtLeast(0.2f)
        val my = cy + faceH * 0.27f + lookPxY * 0.4f
        val mx = cx + lookPxX * 0.5f
        val skew = pos[M_SKEW] * mw
        path.reset()
        path.moveTo(mx - mw / 2, my + skew / 2)
        path.quadTo(mx, my + pos[M_CURVE] * mw * 2f, mx + mw / 2, my - skew / 2)
        mouthPaint.color = glowColor
        mouthPaint.alpha = (22 * glow).toInt().coerceIn(0, 255)
        mouthPaint.strokeWidth = mt + faceH * 0.03f
        canvas.drawPath(path, mouthPaint)
        mouthPaint.color = mouthColor
        mouthPaint.alpha = (255 * min(1f, 0.55f + glow * 0.45f) * flicker).toInt().coerceIn(0, 255)
        mouthPaint.strokeWidth = mt
        canvas.drawPath(path, mouthPaint)

        if (e == Expression.SLEEPING) drawZs(canvas, now, cx + faceW * 0.30f, cy - faceH * 0.12f, faceH, eyeColor)
        canvas.restore()

        if (compact != null) {
            textPaint.textSize = h * 0.07f
            textPaint.color = Look.c(ColorRole.CLOCK)
            textPaint.alpha = 255
            canvas.drawText(compact, w / 2, h * 0.13f, textPaint)
        }
        drawCaption(canvas, now, w / 2, h - h * 0.06f, h, warn)
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, w: Float, rawH: Float, tilt: Float, glow: Float, flicker: Float) {
        val h = rawH.coerceAtLeast(w * 0.18f)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(tilt)
        for (i in 3 downTo 1) {
            val g = w * 0.10f * i
            glowPaint.alpha = (18 * glow / i * 1.6f).toInt().coerceIn(0, 255)
            rect.set(-w / 2 - g, -h / 2 - g, w / 2 + g, h / 2 + g)
            canvas.drawOval(rect, glowPaint)
        }
        eyePaint.alpha = (255 * min(1f, 0.55f + glow * 0.45f) * flicker).toInt().coerceIn(0, 255)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        canvas.drawOval(rect, eyePaint)
        if (h > w * 0.6f) {
            innerPaint.alpha = (110 * min(1f, glow)).toInt().coerceIn(0, 255)
            rect.set(-w * 0.28f, -h * 0.36f, w * 0.02f, -h * 0.08f)
            canvas.drawOval(rect, innerPaint)
        }
        canvas.restore()
    }

    private fun drawZs(canvas: Canvas, now: Long, x: Float, y: Float, faceH: Float, color: Int) {
        textPaint.color = color
        for (i in 0 until 3) {
            val t = ((now / 2600f) + i / 3f) % 1f
            textPaint.textSize = faceH * (0.07f + 0.05f * t)
            textPaint.alpha = (200 * sin(t * Math.PI)).toInt().coerceIn(0, 255)
            canvas.drawText("z", x + t * faceH * 0.12f, y - t * faceH * 0.22f, textPaint)
        }
    }

    /** One quiet line under the face: test progress, error status, or the first-run hint. */
    private fun drawCaption(canvas: Canvas, now: Long, x: Float, y: Float, h: Float, warn: Int) {
        textPaint.textSize = h * 0.038f
        val forced = brain.forced
        val status = brain.status
        val age = now - startedAt
        val (text, alpha) = when {
            testIndex >= 0 && forced != null ->
                "${forced.ordinal + 1} / ${Expression.entries.size}   ${forced.label}   ·   tap to stop" to 170
            status != null -> status to 220
            age < HINT_MS -> "tap me  ·  hold for menu  ·  swipe for more" to
                (120 * ((HINT_MS - age) / 1_500f).coerceIn(0f, 1f)).toInt()
            else -> return
        }
        textPaint.color = if (status != null && testIndex < 0) warn else Look.c(ColorRole.TEXT_2)
        textPaint.alpha = alpha
        canvas.drawText(text, x, y, textPaint)
    }

    private companion object {
        const val HINT_MS = 7_000L
        const val L_OPEN = 0; const val R_OPEN = 1; const val EYE_W = 2
        const val L_TILT = 3; const val R_TILT = 4; const val L_LIFT = 5; const val R_LIFT = 6
        const val M_W = 7; const val M_T = 8; const val M_CURVE = 9; const val M_SKEW = 10
        const val GLOW = 11; const val ALERT = 12
        const val N = 13

        fun write(a: FloatArray, s: FaceShape) {
            a[L_OPEN] = s.leftOpen; a[R_OPEN] = s.rightOpen; a[EYE_W] = s.eyeWidth
            a[L_TILT] = s.leftTilt; a[R_TILT] = s.rightTilt
            a[L_LIFT] = s.leftLift; a[R_LIFT] = s.rightLift
            a[M_W] = s.mouthWidth; a[M_T] = s.mouthThickness
            a[M_CURVE] = s.mouthCurve; a[M_SKEW] = s.mouthSkew
            a[GLOW] = s.glow; a[ALERT] = s.alert
        }
    }
}
