package com.nova.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.nova.core.DataHub
import com.nova.integrations.MediaHub
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Music View (spec v1.1 §14.3): artwork, track/artist, progress, transport controls, the
 * active destination (phone speaker / Bluetooth route / Spotify Connect device) with a
 * "Change" button, and a music-reactive Pixel Pet. Variation 1 is a big dancing-face layout.
 */
class MusicView(context: Context) : View(context) {

    var onChooseDestination: (() -> Unit)? = null
    var onOpenPlayer: (() -> Unit)? = null
    var variation = 0
        set(value) { field = value; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val art = RectF(); private val prev = RectF(); private val play = RectF(); private val next = RectF()
    private val dest = RectF(); private val bar = RectF()
    private var running = false
    private val dataListener = { invalidate() }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, if (DataHub.media?.playing == true || Look.transitioning) 40L else 1_000L)
        }
    }

    fun resume() { if (!running) { running = true; DataHub.addListener(dataListener); post(tick) } }
    fun pause() { running = false; removeCallbacks(tick); DataHub.removeListener(dataListener) }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, role: ColorRole, face: android.graphics.Typeface, maxW: Float, align: Paint.Align = Paint.Align.LEFT) {
        tp.textSize = size; tp.typeface = face; tp.color = Look.c(role); tp.textAlign = align
        c.drawText(TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString(), x, y, tp)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Look.c(ColorRole.BACKGROUND))
        val w = width.toFloat(); val h = height.toFloat()
        val m = DataHub.media
        val now = SystemClock.uptimeMillis()
        val playing = m?.playing == true
        val beat = if (playing) abs(sin(now / 500.0 * Math.PI)).toFloat() else 0f

        if (variation == 1) { dancingFace(c, w, h, beat, playing); return }

        // Artwork (or a placeholder with the NØVA eyes).
        val s = h * 0.62f
        art.set(w * 0.06f, (h - s) / 2 - h * 0.04f, w * 0.06f + s, (h + s) / 2 - h * 0.04f)
        val bmp = m?.art
        if (bmp != null && !bmp.isRecycled) {
            c.save(); c.clipPath(Path().apply { addRoundRect(art, s * 0.06f, s * 0.06f, Path.Direction.CW) })
            p.alpha = (255 * (1 - Look.night * 0.6f)).toInt(); c.drawBitmap(bmp, null, art, p); p.alpha = 255
            c.restore()
        } else {
            p.color = Look.c(ColorRole.TILE); c.drawRoundRect(art, s * 0.06f, s * 0.06f, p)
            eyes(c, art.centerX(), art.centerY(), s * 0.12f, beat)
        }

        val x = art.right + w * 0.05f
        val maxW = w - x - w * 0.05f
        if (m == null) {
            text(c, "Nothing playing", x, h * 0.36f, h * 0.075f, ColorRole.TEXT, Theme.medium, maxW)
            text(c, DataHub.mediaStatus, x, h * 0.46f, h * 0.045f, ColorRole.TEXT_2, Theme.regular, maxW)
            text(c, "Open Spotify ›", x, h * 0.58f, h * 0.045f, ColorRole.ACCENT, Theme.medium, maxW)
            play.set(x, h * 0.5f, x + maxW, h * 0.62f)
        } else {
            text(c, m.title, x, h * 0.25f, h * 0.075f, ColorRole.TEXT, Theme.medium, maxW)
            text(c, m.artist, x, h * 0.34f, h * 0.05f, ColorRole.TEXT_2, Theme.regular, maxW)
            text(c, m.app + if (m.album.isNotBlank()) "  ·  ${m.album}" else "", x, h * 0.41f, h * 0.035f, ColorRole.MUSIC, Theme.regular, maxW)

            // Progress.
            val by = h * 0.5f
            bar.set(x, by - h * 0.03f, x + maxW, by + h * 0.03f)
            p.color = Look.c(ColorRole.TILE_BORDER); p.strokeWidth = h * 0.012f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
            c.drawLine(x, by, x + maxW, by, p)
            val pos = DataHub.mediaPosition()
            val frac = if (m.durationMs > 0) (pos.toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
            p.color = Look.c(ColorRole.MUSIC); c.drawLine(x, by, x + maxW * frac, by, p)
            p.style = Paint.Style.FILL
            text(c, fmt(pos), x, by + h * 0.06f, h * 0.032f, ColorRole.TEXT_2, Theme.regular, maxW)
            text(c, fmt(m.durationMs), x + maxW, by + h * 0.06f, h * 0.032f, ColorRole.TEXT_2, Theme.regular, maxW, Paint.Align.RIGHT)

            // Controls.
            val cy = h * 0.68f; val r = h * 0.075f
            val cx = x + maxW / 2
            prev.set(cx - r * 4.2f, cy - r, cx - r * 2.2f, cy + r)
            play.set(cx - r * 1.2f, cy - r * 1.2f, cx + r * 1.2f, cy + r * 1.2f)
            next.set(cx + r * 2.2f, cy - r, cx + r * 4.2f, cy + r)
            p.color = Look.c(ColorRole.TEXT); c.drawCircle(play.centerX(), play.centerY(), r * 1.15f, p)
            p.color = Look.c(ColorRole.BACKGROUND)
            if (playing) { c.drawRect(cx - r * 0.42f, cy - r * 0.45f, cx - r * 0.14f, cy + r * 0.45f, p); c.drawRect(cx + r * 0.14f, cy - r * 0.45f, cx + r * 0.42f, cy + r * 0.45f, p) }
            else c.drawPath(Path().apply { moveTo(cx - r * 0.3f, cy - r * 0.5f); lineTo(cx + r * 0.55f, cy); lineTo(cx - r * 0.3f, cy + r * 0.5f); close() }, p)
            p.color = Look.c(ColorRole.TEXT)
            skip(c, prev.centerX(), cy, r * 0.55f, -1); skip(c, next.centerX(), cy, r * 0.55f, 1)
        }

        // Destination.
        val connect = DataHub.spotifyDevice
        val destText = "Playing on: " + (if (connect.isNotBlank()) "$connect (Spotify Connect)" else DataHub.audioRoute)
        dest.set(x, h * 0.82f, x + maxW, h * 0.92f)
        p.color = Look.c(ColorRole.TILE); c.drawRoundRect(dest, dest.height() / 2, dest.height() / 2, p)
        text(c, destText, dest.left + h * 0.04f, dest.centerY() + h * 0.014f, h * 0.036f, ColorRole.TEXT, Theme.regular, dest.width() * 0.7f)
        text(c, "Change ›", dest.right - h * 0.04f, dest.centerY() + h * 0.014f, h * 0.036f, ColorRole.ACCENT, Theme.medium, dest.width() * 0.3f, Paint.Align.RIGHT)

        // Little music-reactive eyes in the corner.
        if (bmp != null) eyes(c, w - h * 0.12f, h * 0.1f, h * 0.035f, beat)
    }

    private fun eyes(c: Canvas, cx: Float, cy: Float, s: Float, beat: Float) {
        p.color = Look.c(ColorRole.EYES)
        val bounce = beat * s * 0.5f
        for (dx in floatArrayOf(-1f, 1f)) {
            c.drawOval(RectF(cx + dx * s * 1.1f - s * 0.45f, cy - s - bounce, cx + dx * s * 1.1f + s * 0.45f, cy + s - bounce), p)
        }
    }

    private fun dancingFace(c: Canvas, w: Float, h: Float, beat: Float, playing: Boolean) {
        val fw = h * 1.1f; val fh = fw * 0.58f
        val cx = w / 2; val cy = h * 0.42f - beat * h * 0.04f
        c.save(); c.rotate(if (playing) sin(SystemClock.uptimeMillis() / 1000.0 * Math.PI).toFloat() * 4f else 0f, cx, cy)
        p.color = Look.c(ColorRole.FACEPLATE)
        c.drawRoundRect(RectF(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2), fh * 0.22f, fh * 0.22f, p)
        p.color = Look.c(ColorRole.EYES)
        val eo = if (playing) 0.6f else 1f
        for (dx in floatArrayOf(-0.19f, 0.19f)) {
            val ex = cx + dx * fw
            c.drawOval(RectF(ex - fw * 0.06f, cy - fh * 0.06f - fh * 0.2f * eo, ex + fw * 0.06f, cy - fh * 0.06f + fh * 0.2f * eo), p)
        }
        p.color = Look.c(ColorRole.MOUTH); p.style = Paint.Style.STROKE; p.strokeWidth = fh * 0.045f; p.strokeCap = Paint.Cap.ROUND
        val my = cy + fh * 0.27f
        c.drawPath(Path().apply { moveTo(cx - fw * 0.06f, my); quadTo(cx, my + (if (playing) fh * 0.08f else 0f), cx + fw * 0.06f, my) }, p)
        p.style = Paint.Style.FILL
        c.restore()
        val m = DataHub.media
        text(c, m?.let { "${it.title} — ${it.artist}" } ?: "Nothing playing", w / 2, h * 0.86f, h * 0.05f, ColorRole.TEXT, Theme.medium, w * 0.9f, Paint.Align.CENTER)
        play.set(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2); prev.setEmpty(); next.setEmpty(); dest.setEmpty()
    }

    private fun skip(c: Canvas, cx: Float, cy: Float, s: Float, dir: Int) {
        val path = Path()
        for (k in 0..1) {
            val ox = cx + dir * (k - 0.5f) * s * 0.9f
            path.moveTo(ox - dir * s * 0.45f, cy - s * 0.5f); path.lineTo(ox + dir * s * 0.45f, cy); path.lineTo(ox - dir * s * 0.45f, cy + s * 0.5f); path.close()
        }
        c.drawPath(path, p)
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when {
                dest.contains(e.x, e.y) -> onChooseDestination?.invoke()
                DataHub.media == null && play.contains(e.x, e.y) -> onOpenPlayer?.invoke()
                play.contains(e.x, e.y) -> MediaHub.playPause()
                prev.contains(e.x, e.y) -> MediaHub.previous()
                next.contains(e.x, e.y) -> MediaHub.next()
                bar.contains(e.x, e.y) -> DataHub.media?.let { m -> if (m.durationMs > 0) MediaHub.seekTo(((e.x - bar.left) / bar.width() * m.durationMs).toLong()) }
                else -> return false
            }
            invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
}
