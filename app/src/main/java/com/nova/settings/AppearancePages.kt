package com.nova.settings

import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Palette
import com.nova.ui.ThemePreset

/** Settings → Appearance: theme presets, custom colours per role, and the colour picker. */
object AppearancePages {

    fun appearance(): SettingsPage = SettingsPage("Appearance") {
        val previewH = screen.dp(170)
        section {
            custom(ThemePreviewView(screen.context, Look.palette), previewH)
        }
        section("Theme", footer = "Photo Ambient takes its accent from the photo on screen. " +
            "Custom uses your own colours — edit any colour below and NØVA switches to Custom automatically.") {
            ThemePreset.entries.forEach { preset ->
                val selected = Look.preset == preset
                action((if (selected) "✓  " else "     ") + preset.label,
                    color = if (selected) Look.c(ColorRole.ACCENT) else Look.c(ColorRole.TEXT)) {
                    Prefs.themePreset = preset.name
                }
            }
        }
        section("Colours", footer = "Choose a colour for each part of NØVA.") {
            nav("Customise colours", if (Look.preset == ThemePreset.CUSTOM) "Custom" else "") { colours() }
        }
    }

    fun colours(): SettingsPage = SettingsPage("Colours") {
        section {
            custom(ThemePreviewView(screen.context, Look.palette), screen.dp(150))
        }
        ColorRole.entries.groupBy { it.group }.forEach { (group, roles) ->
            section(group) {
                roles.forEach { role ->
                    swatch(role.label, Look.palette[role]) { screen.push(picker(role)) }
                }
            }
        }
        section(footer = "Starts your custom theme from the currently selected preset.") {
            ThemePreset.entries.filter { it != ThemePreset.CUSTOM && it != ThemePreset.PHOTO_AMBIENT }.forEach { preset ->
                action("Start from ${preset.label}") {
                    screen.confirm("Replace custom colours?", "Your custom colours will be replaced by ${preset.label}.", "Replace") {
                        Prefs.customPalette = preset.palette().toJson()
                        Prefs.themePreset = ThemePreset.CUSTOM.name
                    }
                }
            }
        }
    }

    fun picker(role: ColorRole): SettingsPage = SettingsPage(role.label) {
        val current = Look.palette[role]
        val preview = ThemePreviewView(screen.context, Look.palette)
        section { custom(preview, screen.dp(140)) }
        section(footer = "Drag in the square for shade, the bar for hue, or tap a swatch. Changes save when you lift your finger.") {
            val pickerView = ColorPickerView(screen.context, current, current).apply {
                onChange = { c -> preview.palette = Look.palette.with(role, c); preview.invalidate() }
                onCommit = { c -> setRole(role, c) }
            }
            custom(pickerView, screen.dp(230))
        }
        section {
            textField("Hex code", String.format("#%06X", current and 0xFFFFFF), hint = "#36DFFF") { s ->
                runCatching { Palette.parseColor(s) }.onSuccess { setRole(role, it) }
            }
            action("Reset to NØVA default") { setRole(role, ThemePreset.NOVA[role]) }
        }
    }

    /** Write one role into the custom palette, switching to the Custom theme. */
    fun setRole(role: ColorRole, color: Int) {
        val base = if (Look.preset == ThemePreset.CUSTOM) Look.customPalette() else Look.palette
        var next = base.with(role, color)
        // Parts that were matching the eyes keep matching (glow, mouth), so one change recolours the face.
        if (role == ColorRole.EYES) {
            if (base[ColorRole.GLOW] == base[ColorRole.EYES]) next = next.with(ColorRole.GLOW, color)
            if (base[ColorRole.MOUTH] == base[ColorRole.EYES]) next = next.with(ColorRole.MOUTH, color)
        }
        Prefs.customPalette = next.toJson()
        if (Prefs.themePreset != ThemePreset.CUSTOM.name) Prefs.themePreset = ThemePreset.CUSTOM.name
    }
}
