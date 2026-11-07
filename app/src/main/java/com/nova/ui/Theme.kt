package com.nova.ui

import android.graphics.Color
import android.graphics.Typeface
import com.nova.settings.NightStyle

/**
 * Visual language: NØVA's cyan identity on true black, with Apple-inspired dark UI
 * surfaces (rounded cards, quiet grey secondary text, generous spacing) and a
 * StandBy-inspired night look that can turn the whole screen deep red.
 */
object Theme {
    val CYAN = Color.rgb(0x22, 0xE5, 0xFF)
    val AMBER = Color.rgb(0xFF, 0xB0, 0x20)
    val FACE = Color.rgb(0x0B, 0x0F, 0x14)
    val FACE_EDGE = Color.rgb(0x16, 0x1E, 0x27)

    // Dark "grouped" surfaces in the style of iOS.
    val CARD = Color.rgb(0x1C, 0x1C, 0x1E)
    val CARD_HIGHLIGHT = Color.rgb(0x2C, 0x2C, 0x2E)
    val SEPARATOR = Color.rgb(0x38, 0x38, 0x3A)
    val LABEL = Color.WHITE
    val SECONDARY = Color.argb(0x99, 0xEB, 0xEB, 0xF5)
    val TERTIARY = Color.argb(0x4D, 0xEB, 0xEB, 0xF5)

    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * Apply the night look to a colour. [amount] 0 = day, 1 = full night.
     * RED maps brightness onto a deep red (StandBy-style); DIM just darkens.
     */
    fun night(color: Int, amount: Float, style: NightStyle): Int {
        if (amount <= 0f) return color
        val a = Color.alpha(color)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val (nr, ng, nb) = when (style) {
            NightStyle.DIM -> Triple(r * 0.45f, g * 0.45f, b * 0.45f)
            NightStyle.RED -> {
                val lum = (0.3f * r + 0.59f * g + 0.11f * b)
                Triple(lum * 0.85f, lum * 0.06f, lum * 0.05f)
            }
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
}
