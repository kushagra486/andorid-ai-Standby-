package com.nova.ui

import android.graphics.Color
import android.graphics.Typeface
import com.nova.storage.NightPalette

/** Fonts and colour maths shared by every renderer. Colours themselves live in [Look]. */
object Theme {
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    /**
     * Apply a night palette to a colour. [amount] 0 = day, 1 = full night.
     * DIM_CYAN darkens and cools; RED_AMBER maps brightness onto deep red with an amber lift
     * for bright elements; NEAR_BLACK keeps only a faint grey silhouette.
     */
    fun night(color: Int, amount: Float, palette: NightPalette): Int {
        if (amount <= 0f) return color
        val a = Color.alpha(color)
        val r = Color.red(color).toFloat(); val g = Color.green(color).toFloat(); val b = Color.blue(color).toFloat()
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        val (nr, ng, nb) = when (palette) {
            NightPalette.DIM_CYAN -> Triple(r * 0.25f, g * 0.42f, b * 0.48f)
            NightPalette.RED_AMBER -> Triple(lum * 0.85f, lum * (0.04f + 0.18f * (lum / 255f) * (lum / 255f)), lum * 0.04f)
            NightPalette.NEAR_BLACK -> Triple(lum * 0.22f, lum * 0.22f, lum * 0.24f)
        }
        return Color.argb(
            a,
            (r + (nr - r) * amount).toInt().coerceIn(0, 255),
            (g + (ng - g) * amount).toInt().coerceIn(0, 255),
            (b + (nb - b) * amount).toInt().coerceIn(0, 255),
        )
    }

    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt(),
        )
    }

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Readable text colour (black or white) on top of [bg]. */
    fun onColor(bg: Int): Int {
        val lum = 0.3f * Color.red(bg) + 0.59f * Color.green(bg) + 0.11f * Color.blue(bg)
        return if (lum > 150) Color.BLACK else Color.WHITE
    }
}
