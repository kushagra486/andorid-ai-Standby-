package com.nova.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.nova.ui.ColorRole
import com.nova.ui.Palette
import com.nova.ui.Theme

/** Miniature of the Pixel Pet + a clock tile + a switch, painted with a given palette. */
class ThemePreviewView(context: Context, var palette: Palette) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pal = palette
        p.color = pal[ColorRole.BACKGROUND]
        r.set(0f, 0f, w, h); canvas.drawRoundRect(r, h * 0.08f, h * 0.08f, p)

        // Face.
        val fw = w * 0.42f; val fh = fw * 0.58f
        val fx = w * 0.05f; val fy = (h - fh) / 2
        r.set(fx, fy, fx + fw, fy + fh)
        p.color = pal[ColorRole.FACEPLATE]; canvas.drawRoundRect(r, fh * 0.22f, fh * 0.22f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = pal[ColorRole.TILE_BORDER]
        canvas.drawRoundRect(r, fh * 0.22f, fh * 0.22f, p); p.style = Paint.Style.FILL
        val ew = fw * 0.12f; val eh = fh * 0.4f
        for (dx in floatArrayOf(-0.19f, 0.19f)) {
            val cx = fx + fw / 2 + dx * fw; val cy = fy + fh * 0.44f
            p.color = Theme.withAlpha(pal[ColorRole.GLOW], 50)
            r.set(cx - ew * 0.75f, cy - eh * 0.6f, cx + ew * 0.75f, cy + eh * 0.6f); canvas.drawOval(r, p)
            p.color = pal[ColorRole.EYES]
            r.set(cx - ew / 2, cy - eh / 2, cx + ew / 2, cy + eh / 2); canvas.drawOval(r, p)
        }
        p.color = pal[ColorRole.MOUTH]
        val mw = fw * 0.1f; val mt = fh * 0.05f; val my = fy + fh * 0.77f
        r.set(fx + fw / 2 - mw / 2, my - mt / 2, fx + fw / 2 + mw / 2, my + mt / 2); canvas.drawRoundRect(r, mt, mt, p)

        // Clock tile.
        val tx = w * 0.52f; val tw = w * 0.43f
        r.set(tx, h * 0.12f, tx + tw, h * 0.62f)
        p.color = pal[ColorRole.TILE]; canvas.drawRoundRect(r, h * 0.06f, h * 0.06f, p)
        p.style = Paint.Style.STROKE; p.color = pal[ColorRole.TILE_BORDER]; canvas.drawRoundRect(r, h * 0.06f, h * 0.06f, p)
        p.style = Paint.Style.FILL
        p.typeface = Theme.light; p.textAlign = Paint.Align.CENTER
        p.color = pal[ColorRole.CLOCK]; p.textSize = h * 0.26f
        canvas.drawText("9:41", r.centerX(), r.centerY() + p.textSize * 0.25f, p)
        p.color = pal[ColorRole.TEXT_2]; p.textSize = h * 0.07f
        canvas.drawText("Saturday", r.centerX(), r.bottom - h * 0.04f, p)

        // Text + switch + accent chip.
        p.textAlign = Paint.Align.LEFT; p.color = pal[ColorRole.TEXT]; p.textSize = h * 0.08f; p.typeface = Theme.regular
        canvas.drawText("Sounds", tx, h * 0.8f, p)
        val sw = h * 0.2f; val sh = h * 0.12f
        r.set(tx + tw - sw, h * 0.8f - sh * 0.8f, tx + tw, h * 0.8f + sh * 0.2f)
        p.color = pal[ColorRole.SWITCH_ON]; canvas.drawRoundRect(r, sh / 2, sh / 2, p)
        p.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(r.right - sh / 2, r.centerY(), sh * 0.42f, p)
        p.color = pal[ColorRole.ACCENT]; p.textSize = h * 0.07f
        canvas.drawText("Done", tx, h * 0.93f, p)
        p.color = pal[ColorRole.ACCENT_2]
        canvas.drawText("Edit", tx + tw * 0.4f, h * 0.93f, p)
    }
}
