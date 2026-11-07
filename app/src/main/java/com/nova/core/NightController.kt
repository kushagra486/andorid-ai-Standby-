package com.nova.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nova.storage.NightMode
import com.nova.storage.Prefs
import com.nova.ui.Look
import java.util.Calendar

/** Pure night-mode decision rules (unit-tested). */
object NightRules {
    fun inSchedule(hour: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> hour in start until end            // e.g. 01–06
        else -> hour >= start || hour < end               // e.g. 22–07 wraps midnight
    }

    /**
     * @param darkSinceMs how long the room has been continuously dark (null = sensor not used/unavailable)
     * Automatic = schedule, plus ambient-light adaptation when a validated sensor reports a dark room
     * for at least two minutes. Manual ON/OFF always wins.
     */
    fun decide(mode: NightMode, hour: Int, start: Int, end: Int, darkSinceMs: Long?): Boolean = when (mode) {
        NightMode.ON -> true
        NightMode.OFF -> false
        NightMode.AUTO -> inSchedule(hour, start, end) || (darkSinceMs != null && darkSinceMs >= 120_000L)
    }

    /** Hysteresis so lamps flickering around the threshold don't toggle night mode. */
    fun isDark(lux: Float, currentlyDark: Boolean): Boolean = if (currentlyDark) lux < 12f else lux < 4f
}

/**
 * Drives automatic night mode (spec v1.1 §14.7): schedule, optional ambient-light adaptation
 * after the sensor has been validated on this phone, and manual override. Falls back to the
 * schedule whenever the sensor is missing, unreliable or disabled.
 */
class NightController(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val light: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())
    private var dark = false
    private var darkSince = 0L
    private var sensorOn = false
    var onChanged: ((Boolean) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() { evaluate(); handler.postDelayed(this, 30_000) }
    }

    val hasSensor: Boolean get() = light != null

    fun start() {
        handler.removeCallbacks(tick); handler.post(tick)
        val wantSensor = Prefs.useLightSensor && light != null && Prefs.nightMode == NightMode.AUTO
        if (wantSensor && !sensorOn) { sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL); sensorOn = true }
        if (!wantSensor && sensorOn) { sm.unregisterListener(this); sensorOn = false; dark = false }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        if (sensorOn) { sm.unregisterListener(this); sensorOn = false }
    }

    fun evaluate(animate: Boolean = true) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val darkFor = if (sensorOn && dark) SystemClock.elapsedRealtime() - darkSince else null
        val night = NightRules.decide(Prefs.nightMode, hour, Prefs.nightStartHour, Prefs.nightEndHour, darkFor)
        val was = Look.isNight
        Look.setNight(night, animate)
        if (night != was) onChanged?.invoke(night)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val nowDark = NightRules.isDark(e.values[0], dark)
        if (nowDark && !dark) darkSince = SystemClock.elapsedRealtime()
        dark = nowDark
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Capability test: listen for [ms] while the user covers/uncovers the sensor. The sensor is
     * considered usable only if it reports values that actually change.
     */
    fun testSensor(ms: Long = 8_000, done: (String, Boolean) -> Unit) {
        val s = light ?: run { done("No light sensor on this phone", false); return }
        var min = Float.MAX_VALUE; var max = -1f; var count = 0
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { count++; min = minOf(min, e.values[0]); max = maxOf(max, e.values[0]) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        handler.postDelayed({
            sm.unregisterListener(l)
            val ok = count >= 2 && max - min >= 5f
            val msg = when {
                count == 0 -> "Sensor did not respond"
                !ok -> "Readings did not change (${min.toInt()}–${max.toInt()} lux)"
                else -> "Working (${min.toInt()}–${max.toInt()} lux)"
            }
            done(msg, ok)
        }, ms)
    }
}
