package com.nova.storage

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class Volume(val label: String, val gain: Float) { LOW("Low", 0.35f), MEDIUM("Medium", 0.65f), HIGH("High", 1f) }
enum class Intensity(val label: String) { LOW("Calm (reduced motion)"), NORMAL("Normal"), HIGH("Lively") }
enum class NightMode(val label: String) { AUTO("Automatic"), ON("Always on"), OFF("Off") }
enum class NightPalette(val label: String) { DIM_CYAN("Dim cyan"), RED_AMBER("Red / amber"), NEAR_BLACK("Near-black") }
enum class ClockFormat(val label: String) { AUTO("Follow phone"), H12("12-hour"), H24("24-hour") }
enum class KeepAwake(val label: String) { ALWAYS("Always"), CHARGING("While charging"), SYSTEM("Use phone timeout") }
enum class TempUnit(val label: String) { C("°C"), F("°F") }
enum class ChargingView(val label: String) { PET("Pixel Pet"), CLOCK("Clock"), PHOTOS("Photo frame") }

/**
 * All persisted settings, backed by SharedPreferences. Every write notifies listeners so
 * screens can refresh. Larger structures (layouts, palettes, reminders) are stored as
 * versioned JSON strings by their own stores.
 */
object Prefs {
    private var sp: SharedPreferences? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences("nova", Context.MODE_PRIVATE)
    }

    val raw: SharedPreferences get() = sp ?: error("Prefs.init() not called")

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() = listeners.toList().forEach { it() }

    // ---- Sound ---------------------------------------------------------------------------
    var soundOn by bool("soundOn", true)
    var volume by enum("volume", Volume.MEDIUM)

    // ---- Pet / display -------------------------------------------------------------------
    var intensity by enum("intensity", Intensity.NORMAL)
    var keepAwake by enum("keepAwake", KeepAwake.ALWAYS)
    var idleReturnSec by int("idleReturnSec", 60)
    var chargingView by enum("chargingView", ChargingView.PET)
    var largeText by bool("largeText", false)

    // ---- Night ---------------------------------------------------------------------------
    var nightMode by enum("nightMode2", NightMode.AUTO)
    var nightStartHour by int("nightStart", 22)
    var nightEndHour by int("nightEnd", 7)
    var nightPalette by enum("nightPalette", NightPalette.RED_AMBER)
    var useLightSensor by bool("useLightSensor", false)
    var lightSensorStatus by string("lightSensorStatus", "Not tested")
    var nightKeepClock by bool("nightKeepClock", false)

    // ---- Theme ---------------------------------------------------------------------------
    var themePreset by string("themePreset", "NOVA_CYAN")
    var customPalette by string("customPalette", "")

    // ---- Clock ---------------------------------------------------------------------------
    var clockFormat by enum("clockFormat", ClockFormat.AUTO)
    var showSeconds by bool("showSeconds", false)

    // ---- Navigation state ----------------------------------------------------------------
    var lastFamily by string("lastFamily", "PET")
    fun variation(family: String): Int = raw.getInt("var_$family", 0)
    fun setVariation(family: String, v: Int) { raw.edit().putInt("var_$family", v).apply() }
    var disabledFamilies by string("disabledFamilies", "")

    // ---- Weather -------------------------------------------------------------------------
    var weatherPlace by string("weatherPlace", "")
    var weatherLat by float("weatherLat", Float.NaN)
    var weatherLon by float("weatherLon", Float.NaN)
    var tempUnit by enum("tempUnit", TempUnit.C)

    // ---- Photos --------------------------------------------------------------------------
    var photoTreeUri by string("photoTreeUri", "")
    var photoIntervalSec by int("photoInterval", 15)
    var photoShuffle by bool("photoShuffle", true)
    var photoClockOverlay by bool("photoClock", true)
    var photoOverlayCorner by int("photoCorner", 2)       // 0 TL, 1 TR, 2 BL, 3 BR
    var photoOverlayOpacity by int("photoOpacity", 80)    // %

    // ---- Voice / AI ----------------------------------------------------------------------
    var speakReplies by bool("speakReplies", true)
    var voiceLanguage by string("voiceLanguage", "en-IN")
    var aiEnabled by bool("aiEnabled", false)
    var aiEndpoint by string("aiEndpoint", "")
    var aiToken by string("aiToken", "")
    var wakeWordEnabled by bool("wakeWord", false)
    var wakeSensitivity by int("wakeSensitivity", 2)       // 1 strict .. 3 loose

    // ---- Music / Spotify -----------------------------------------------------------------
    var spotifyClientId by string("spotifyClientId", "")
    var spotifyRefreshToken by string("spotifyRefresh", "")
    var danceWithMusic by bool("danceWithMusic", true)

    // ---- Smart home ----------------------------------------------------------------------
    var smartDevices by string("smartDevices", "[]")

    // ---- Diagnostics ---------------------------------------------------------------------
    var lastCrash by string("lastCrash", "")

    // ---- Delegates -----------------------------------------------------------------------
    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = raw.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            raw.edit().putBoolean(key, value).apply(); notifyChanged()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = raw.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            raw.edit().putInt(key, value).apply(); notifyChanged()
        }
    }

    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = raw.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            raw.edit().putFloat(key, value).apply(); notifyChanged()
        }
    }

    private fun string(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = raw.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            raw.edit().putString(key, value).apply(); notifyChanged()
        }
    }

    private inline fun <reified T : Enum<T>> enum(key: String, def: T) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            val n = raw.getString(key, null) ?: return def
            return enumValues<T>().firstOrNull { it.name == n } ?: def
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            raw.edit().putString(key, value.name).apply(); notifyChanged()
        }
    }
}
