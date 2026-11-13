package com.nova.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import com.nova.ui.ThemePreset

/**
 * One-tap colour palette for the top of Settings: every theme as a mini NØVA face drawn in that
 * theme's colours, with its accent swatches underneath. Tap to apply; the current one is ringed.
 */
class ThemeStripView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val presets = ThemePreset.entries
    private val cells = Array(presets.size) { RectF() }

    init { contentDescription = "Colour themes" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cw = w / presets.size
        presets.forEachIndexed { i, preset ->
            val pal = preset.palette(if (preset == ThemePreset.CUSTOM) Look.customPalette() else null)
            val cell = cells[i].apply { set(i * cw, 0f, (i + 1) * cw, h) }
            val selected = Look.preset == preset
            // Mini faceplate in the theme's own colours.
            val fw = cw * 0.74f; val fh = h * 0.42f
            r.set(cell.centerX() - fw / 2, h * 0.08f, cell.centerX() + fw / 2, h * 0.08f + fh)
            p.style = Paint.Style.FILL; p.color = pal[ColorRole.BACKGROUND]
            c.drawRoundRect(RectF(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4), fh * 0.3f, fh * 0.3f, p)
            p.color = pal[ColorRole.FACEPLATE]; c.drawRoundRect(r, fh * 0.26f, fh * 0.26f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = if (selected) h * 0.03f else 2f
            p.color = if (selected) Look.c(ColorRole.ACCENT) else pal[ColorRole.TILE_BORDER]
            c.drawRoundRect(RectF(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6), fh * 0.32f, fh * 0.32f, p)
            p.style = Paint.Style.FILL
            val ew = fw * 0.13f; val eh = fh * 0.5f
            for (dx in floatArrayOf(-0.2f, 0.2f)) {
                val ex = r.centerX() + dx * fw; val ey = r.centerY() - fh * 0.06f
                p.color = Theme.withAlpha(pal[ColorRole.GLOW], 70); c.drawOval(RectF(ex - ew, ey - eh * 0.65f, ex + ew, ey + eh * 0.65f), p)
                p.color = pal[ColorRole.EYES]; c.drawOval(RectF(ex - ew / 2, ey - eh / 2, ex + ew / 2, ey + eh / 2), p)
            }
            p.color = pal[ColorRole.MOUTH]
            c.drawRoundRect(RectF(r.centerX() - fw * 0.08f, r.bottom - fh * 0.2f, r.centerX() + fw * 0.08f, r.bottom - fh * 0.12f), fh, fh, p)
            // Swatches: accent, secondary accent, clock, text.
            val roles = listOf(ColorRole.ACCENT, ColorRole.ACCENT_2, ColorRole.CLOCK, ColorRole.TEXT)
            val sw = fw / roles.size
            roles.forEachIndexed { k, role ->
                p.color = pal[role]
                c.drawCircle(r.left + sw * k + sw / 2, h * 0.62f, sw * 0.32f, p)
            }
            p.textAlign = Paint.Align.CENTER; p.textSize = minOf(h * 0.11f, cw * 0.15f); p.typeface = if (selected) Theme.medium else Theme.regular
            p.color = Look.c(if (selected) ColorRole.ACCENT else ColorRole.TEXT)
            c.drawText(preset.label, cell.centerX(), h * 0.86f, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = cells.indexOfFirst { it.contains(e.x, e.y) }
            if (i >= 0) { Prefs.themePreset = presets[i].name; invalidate() }
        }
        return true
    }
}
