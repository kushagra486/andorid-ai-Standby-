package com.nova.settings

import android.content.SharedPreferences

enum class Volume(val label: String, val gain: Float) {
    LOW("Low", 0.35f), MEDIUM("Medium", 0.65f), HIGH("High", 1f)
}

enum class Intensity(val label: String) {
    LOW("Calm"), NORMAL("Normal"), HIGH("Lively")
}

enum class NightMode(val label: String) {
    OFF("Off"), AUTO("22:00 – 07:00"), ALWAYS("Always")
}

/** Night look, inspired by StandBy: just dimmer, or everything turns deep red. */
enum class NightStyle(val label: String) {
    DIM("Dim"), RED("Red")
}

data class NovaSettings(
    val soundOn: Boolean = true,
    val volume: Volume = Volume.MEDIUM,
    val intensity: Intensity = Intensity.NORMAL,
    val nightMode: NightMode = NightMode.AUTO,
    val nightStyle: NightStyle = NightStyle.RED,
) {
    fun isNight(hour: Int): Boolean = when (nightMode) {
        NightMode.OFF -> false
        NightMode.ALWAYS -> true
        NightMode.AUTO -> hour >= 22 || hour < 7
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean("soundOn", soundOn)
            .putString("volume", volume.name)
            .putString("intensity", intensity.name)
            .putString("nightMode", nightMode.name)
            .putString("nightStyle", nightStyle.name)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences): NovaSettings {
            val d = NovaSettings()
            // v0.1.0 stored only "muted".
            val soundOn = if (prefs.contains("soundOn")) prefs.getBoolean("soundOn", true)
            else !prefs.getBoolean("muted", false)
            return NovaSettings(
                soundOn = soundOn,
                volume = enumOr(prefs.getString("volume", null), d.volume),
                intensity = enumOr(prefs.getString("intensity", null), d.intensity),
                nightMode = enumOr(prefs.getString("nightMode", null), d.nightMode),
                nightStyle = enumOr(prefs.getString("nightStyle", null), d.nightStyle),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback
    }
}
