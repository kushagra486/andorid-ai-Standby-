package com.nova.ui

import android.graphics.Color
import android.os.SystemClock
import com.nova.storage.NightPalette
import com.nova.storage.Prefs
import org.json.JSONObject

/** Every colour the user can customise. Grouped for the Settings colour editor. */
enum class ColorRole(val label: String, val group: String) {
    BACKGROUND("Background", "Screen"),
    TILE("Tiles & cards", "Screen"),
    TILE_BORDER("Tile borders", "Screen"),
    FACEPLATE("Faceplate", "Pixel Pet"),
    EYES("Eyes", "Pixel Pet"),
    MOUTH("Mouth", "Pixel Pet"),
    GLOW("Glow", "Pixel Pet"),
    CLOCK("Clock digits", "Clock"),
    CLOCK_HANDS("Analog hands", "Clock"),
    ACCENT("Accent", "Interface"),
    ACCENT_2("Secondary accent", "Interface"),
    TEXT("Primary text", "Interface"),
    TEXT_2("Secondary text", "Interface"),
    SWITCH_ON("Switches", "Interface"),
    BATTERY("Battery ring", "Widgets"),
    MUSIC("Music highlights", "Widgets"),
    WARNING("Errors & alerts", "Interface");
}

/** A complete set of colours, one per [ColorRole]. Immutable. */
class Palette(private val colors: Map<ColorRole, Int>) {
    operator fun get(role: ColorRole): Int = colors[role] ?: Color.MAGENTA

    fun with(role: ColorRole, color: Int) = Palette(colors + (role to color))

    fun toJson(): String = JSONObject().apply {
        put("schema", 1)
        ColorRole.entries.forEach { put(it.name, String.format("#%08X", this@Palette[it])) }
    }.toString()

    companion object {
        fun fromJson(json: String, fallback: Palette): Palette {
            if (json.isBlank()) return fallback
            return runCatching {
                val o = JSONObject(json)
                Palette(ColorRole.entries.associateWith { r ->
                    if (o.has(r.name)) parseColor(o.getString(r.name)) else fallback[r]
                })
            }.getOrDefault(fallback)
        }

        fun parseColor(s: String): Int {
            val hex = s.trim().removePrefix("#")
            return when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> throw IllegalArgumentException("bad colour $s")
            }
        }

        fun of(vararg pairs: Pair<ColorRole, Int>): Palette = Palette(pairs.toMap())
        fun rgb(hex: Int) = (0xFF000000.toInt() or hex)
    }
}

/** Built-in themes. Hybrid (NØVA Cyan) is the default design direction (spec v1.1 §14.2). */
enum class ThemePreset(val label: String) {
    NOVA_CYAN("NØVA Cyan"), MIDNIGHT("Midnight"), WARM_NIGHT("Warm Night"), FROST("Frost"),
    PHOTO_AMBIENT("Photo Ambient"), CUSTOM("Custom");

    fun palette(custom: Palette? = null): Palette = when (this) {
        NOVA_CYAN -> NOVA
        MIDNIGHT -> Palette.of(
            ColorRole.BACKGROUND to Palette.rgb(0x000000), ColorRole.TILE to Palette.rgb(0x0A0F1F),
            ColorRole.TILE_BORDER to Palette.rgb(0x16203A), ColorRole.FACEPLATE to Palette.rgb(0x070B16),
            ColorRole.EYES to Palette.rgb(0x6F9BFF), ColorRole.MOUTH to Palette.rgb(0x6F9BFF),
            ColorRole.GLOW to Palette.rgb(0x3D6BFF), ColorRole.CLOCK to Palette.rgb(0xA9C1FF),
            ColorRole.CLOCK_HANDS to Palette.rgb(0x6F9BFF), ColorRole.ACCENT to Palette.rgb(0x6F9BFF),
            ColorRole.ACCENT_2 to Palette.rgb(0x9B7BFF), ColorRole.TEXT to Palette.rgb(0xE6ECFF),
            ColorRole.TEXT_2 to Palette.rgb(0x7380A6), ColorRole.SWITCH_ON to Palette.rgb(0x6F9BFF),
            ColorRole.BATTERY to Palette.rgb(0x6F9BFF), ColorRole.MUSIC to Palette.rgb(0x9B7BFF),
            ColorRole.WARNING to Palette.rgb(0xFFB020),
        )
        WARM_NIGHT -> Palette.of(
            ColorRole.BACKGROUND to Palette.rgb(0x0A0604), ColorRole.TILE to Palette.rgb(0x1A110B),
            ColorRole.TILE_BORDER to Palette.rgb(0x2A1B10), ColorRole.FACEPLATE to Palette.rgb(0x120B07),
            ColorRole.EYES to Palette.rgb(0xFFB347), ColorRole.MOUTH to Palette.rgb(0xFFB347),
            ColorRole.GLOW to Palette.rgb(0xFF8C1A), ColorRole.CLOCK to Palette.rgb(0xFFC27A),
            ColorRole.CLOCK_HANDS to Palette.rgb(0xFF9F43), ColorRole.ACCENT to Palette.rgb(0xFF9F43),
            ColorRole.ACCENT_2 to Palette.rgb(0xFF6B3D), ColorRole.TEXT to Palette.rgb(0xFFE9D6),
            ColorRole.TEXT_2 to Palette.rgb(0xB08C70), ColorRole.SWITCH_ON to Palette.rgb(0xFF9F43),
            ColorRole.BATTERY to Palette.rgb(0xFFB347), ColorRole.MUSIC to Palette.rgb(0xFF6B3D),
            ColorRole.WARNING to Palette.rgb(0xFF5A4A),
        )
        FROST -> Palette.of(
            ColorRole.BACKGROUND to Palette.rgb(0x070B10), ColorRole.TILE to Palette.rgb(0x121A24),
            ColorRole.TILE_BORDER to Palette.rgb(0x24313F), ColorRole.FACEPLATE to Palette.rgb(0x0C131B),
            ColorRole.EYES to Palette.rgb(0xDDF3FF), ColorRole.MOUTH to Palette.rgb(0xDDF3FF),
            ColorRole.GLOW to Palette.rgb(0x9AD8FF), ColorRole.CLOCK to Palette.rgb(0xF2FAFF),
            ColorRole.CLOCK_HANDS to Palette.rgb(0x9AD8FF), ColorRole.ACCENT to Palette.rgb(0x9AD8FF),
            ColorRole.ACCENT_2 to Palette.rgb(0x5FA8FF), ColorRole.TEXT to Palette.rgb(0xF4F9FF),
            ColorRole.TEXT_2 to Palette.rgb(0x8FA3B8), ColorRole.SWITCH_ON to Palette.rgb(0x9AD8FF),
            ColorRole.BATTERY to Palette.rgb(0xDDF3FF), ColorRole.MUSIC to Palette.rgb(0x5FA8FF),
            ColorRole.WARNING to Palette.rgb(0xFFB020),
        )
        PHOTO_AMBIENT -> {
            val a = Look.ambientAccent
            NOVA.with(ColorRole.EYES, a).with(ColorRole.MOUTH, a).with(ColorRole.GLOW, a)
                .with(ColorRole.CLOCK, Theme.lerpColor(a, Color.WHITE, 0.35f)).with(ColorRole.ACCENT, a)
                .with(ColorRole.CLOCK_HANDS, a).with(ColorRole.BATTERY, a).with(ColorRole.SWITCH_ON, a)
        }
        CUSTOM -> custom ?: NOVA
    }

    companion object {
        /** Spec v1.1: base #05090D, electric cyan #36DFFF, blue #147CFF, cool white / muted blue-grey. */
        val NOVA = Palette.of(
            ColorRole.BACKGROUND to Palette.rgb(0x05090D), ColorRole.TILE to Palette.rgb(0x0E151C),
            ColorRole.TILE_BORDER to Palette.rgb(0x1A2633), ColorRole.FACEPLATE to Palette.rgb(0x0B1117),
            ColorRole.EYES to Palette.rgb(0x36DFFF), ColorRole.MOUTH to Palette.rgb(0x36DFFF),
            ColorRole.GLOW to Palette.rgb(0x36DFFF), ColorRole.CLOCK to Palette.rgb(0x36DFFF),
            ColorRole.CLOCK_HANDS to Palette.rgb(0x147CFF), ColorRole.ACCENT to Palette.rgb(0x36DFFF),
            ColorRole.ACCENT_2 to Palette.rgb(0x147CFF), ColorRole.TEXT to Palette.rgb(0xEAF6FF),
            ColorRole.TEXT_2 to Palette.rgb(0x7F93A8), ColorRole.SWITCH_ON to Palette.rgb(0x36DFFF),
            ColorRole.BATTERY to Palette.rgb(0x36DFFF), ColorRole.MUSIC to Palette.rgb(0x147CFF),
            ColorRole.WARNING to Palette.rgb(0xFFB020),
        )

        fun byName(name: String) = entries.firstOrNull { it.name == name } ?: NOVA_CYAN
    }
}

/**
 * The live look of the app: current palette plus the night transform. Every renderer reads
 * colours through [c] so themes, custom colours and night mode apply everywhere at once.
 */
object Look {
    var preset: ThemePreset = ThemePreset.NOVA_CYAN
        private set
    var palette: Palette = ThemePreset.NOVA
        private set
    var ambientAccent: Int = Palette.rgb(0x36DFFF)
        private set

    private var nightFrom = 0f
    private var nightTo = 0f
    private var nightChangedAt = 0L
    private const val FADE_MS = 2_000f

    /** Re-read theme settings (call after any theme change). */
    fun reload() {
        preset = ThemePreset.byName(Prefs.themePreset)
        palette = preset.palette(customPalette())
    }

    fun customPalette(): Palette = Palette.fromJson(Prefs.customPalette, ThemePreset.NOVA)

    fun setAmbient(color: Int) {
        // Keep ambient accents bright enough to read on near-black.
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f); hsv[2] = hsv[2].coerceAtLeast(0.85f)
        ambientAccent = Color.HSVToColor(hsv)
        if (preset == ThemePreset.PHOTO_AMBIENT) reload()
    }

    fun setNight(on: Boolean, animate: Boolean = true) {
        val target = if (on) 1f else 0f
        if (target == nightTo) return
        nightFrom = if (animate) night else target
        nightTo = target
        nightChangedAt = SystemClock.uptimeMillis()
    }

    val isNight: Boolean get() = nightTo > 0.5f

    /** 0 = day … 1 = full night, fading over two seconds. */
    val night: Float
        get() {
            val t = ((SystemClock.uptimeMillis() - nightChangedAt) / FADE_MS).coerceIn(0f, 1f)
            return nightFrom + (nightTo - nightFrom) * t
        }

    val transitioning: Boolean get() = night != nightTo

    fun c(role: ColorRole): Int = Theme.night(palette[role], night, Prefs.nightPalette)
    fun c(color: Int): Int = Theme.night(color, night, Prefs.nightPalette)
}
