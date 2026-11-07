package com.nova.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme

/**
 * HSV colour picker: saturation/brightness square, vertical hue bar, a row of quick
 * swatches and a big before/after preview. Reports every change through [onChange].
 */
class ColorPickerView(context: Context, initial: Int, private val original: Int) : View(context) {

    var onChange: ((Int) -> Unit)? = null

    /** Called when the user lifts their finger or taps a swatch: time to save. */
    var onCommit: ((Int) -> Unit)? = null

    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    val color: Int get() = Color.HSVToColor(hsv)

    fun setColor(c: Int) { Color.colorToHSV(c, hsv); invalidate() }

    private val swatches = intArrayOf(
        0xFF36DFFF.toInt(), 0xFF147CFF.toInt(), 0xFF6F9BFF.toInt(), 0xFF9B7BFF.toInt(),
        0xFFFF5FD2.toInt(), 0xFFFF453A.toInt(), 0xFFFF9F43.toInt(), 0xFFFFD60A.toInt(),
        0xFF30D158.toInt(), 0xFF00C7BE.toInt(), 0xFFEAF6FF.toInt(), 0xFF7F93A8.toInt(),
        0xFF0E151C.toInt(), 0xFF05090D.toInt(), 0xFF000000.toInt(),
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svRect = RectF(); private val hueRect = RectF(); private val previewRect = RectF()
    private val swatchRects = Array(swatches.size) { RectF() }
    private var dragging = 0   // 1 sv, 2 hue

    private fun layoutRects() {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = h * 0.04f
        val swatchH = h * 0.14f
        val areaH = h - swatchH - pad * 3
        svRect.set(pad, pad, pad + areaH * 1.35f, pad + areaH)
        hueRect.set(svRect.right + pad * 1.5f, pad, svRect.right + pad * 1.5f + h * 0.09f, pad + areaH)
        previewRect.set(hueRect.right + pad * 1.5f, pad, w - pad, pad + areaH)
        val sw = (w - pad * 2) / swatches.size
        swatches.indices.forEach { i ->
            swatchRects[i].set(pad + i * sw + sw * 0.12f, h - pad - swatchH, pad + (i + 1) * sw - sw * 0.12f, h - pad)
        }
    }

    override fun onDraw(canvas: Canvas) {
        layoutRects()
        val r = height * 0.03f

        // Saturation (x) × value (y) square for the current hue.
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val sat = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        val value = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        p.shader = ComposeShader(value, sat, PorterDuff.Mode.MULTIPLY)
        canvas.drawRoundRect(svRect, r, r, p)
        p.shader = null

        // Hue bar.
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        p.shader = LinearGradient(0f, hueRect.top, 0f, hueRect.bottom, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hueRect, r, r, p)
        p.shader = null

        // Markers.
        p.style = Paint.Style.STROKE; p.strokeWidth = height * 0.008f; p.color = Color.WHITE
        val mx = svRect.left + hsv[1] * svRect.width()
        val my = svRect.top + (1 - hsv[2]) * svRect.height()
        canvas.drawCircle(mx, my, height * 0.035f, p)
        val hy = hueRect.top + hsv[0] / 360f * hueRect.height()
        canvas.drawRoundRect(RectF(hueRect.left - 4, hy - 6, hueRect.right + 4, hy + 6), 4f, 4f, p)
        p.style = Paint.Style.FILL

        // Preview: new colour on top, original below.
        val split = previewRect.top + previewRect.height() * 0.62f
        p.color = color
        canvas.drawRoundRect(RectF(previewRect.left, previewRect.top, previewRect.right, split), r, r, p)
        p.color = original
        canvas.drawRoundRect(RectF(previewRect.left, split + height * 0.02f, previewRect.right, previewRect.bottom), r, r, p)
        p.textAlign = Paint.Align.CENTER; p.textSize = height * 0.05f
        p.color = Theme.onColor(color)
        canvas.drawText(String.format("#%06X", color and 0xFFFFFF), previewRect.centerX(), previewRect.top + (split - previewRect.top) / 2 + p.textSize / 3, p)
        p.color = Theme.onColor(original); p.textSize = height * 0.04f
        canvas.drawText("before", previewRect.centerX(), (split + previewRect.bottom) / 2 + p.textSize / 3, p)

        // Quick swatches.
        swatches.forEachIndexed { i, c ->
            p.color = c
            canvas.drawRoundRect(swatchRects[i], r, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = Theme.withAlpha(Look.c(ColorRole.TEXT), 70)
            canvas.drawRoundRect(swatchRects[i], r, r, p); p.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // don't scroll the page while picking
                dragging = when {
                    svRect.contains(e.x, e.y) -> 1
                    hueRect.contains(e.x, e.y) || (e.x in hueRect.left - 20..hueRect.right + 20 && e.y in hueRect.top..hueRect.bottom) -> 2
                    else -> {
                        swatchRects.indexOfFirst { it.contains(e.x, e.y) }.takeIf { it >= 0 }?.let { i ->
                            Color.colorToHSV(swatches[i], hsv); emit(); onCommit?.invoke(color)
                        }
                        0
                    }
                }
                track(e)
            }
            MotionEvent.ACTION_MOVE -> track(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != 0) onCommit?.invoke(color)
                dragging = 0; parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun track(e: MotionEvent) {
        when (dragging) {
            1 -> {
                hsv[1] = ((e.x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((e.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
                emit()
            }
            2 -> {
                hsv[0] = (((e.y - hueRect.top) / hueRect.height()).coerceIn(0f, 0.999f)) * 360f
                emit()
            }
        }
    }

    private fun emit() { invalidate(); onChange?.invoke(color) }
}
