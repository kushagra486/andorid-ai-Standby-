package com.nova.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Short electronic sounds synthesised in code, so the app ships no audio files and
 * every sound is original. Each sound is rendered once into a static AudioTrack.
 * A [SoundLimiter] keeps NØVA from ever getting noisy.
 */
class SoundFx {

    enum class Sound { CHIRP, DOUBLE_CHIRP, HAPPY, CURIOUS, HMM, THINK, LISTEN, GLITCH, ERROR, SLEEP }

    private val sampleRate = 22050
    private val tracks = HashMap<Sound, AudioTrack>()
    private val limiter = SoundLimiter()

    /** User setting. */
    var enabled = true

    /** Night mode silences everything except explicit tests. */
    var nightMuted = false

    /** 0..1 */
    var volume = 0.65f
        set(value) {
            field = value.coerceIn(0f, 1f)
            tracks.values.forEach { runCatching { it.setVolume(field) } }
        }

    init {
        Sound.entries.forEach { sound ->
            runCatching { tracks[sound] = buildTrack(render(sound)).also { it.setVolume(volume) } }
        }
    }

    /** @param force bypasses night mute and rate limiting (used by the expression test). */
    fun play(sound: Sound, now: Long, force: Boolean = false) {
        if (!enabled) return
        if (!force && (nightMuted || !limiter.allow(now))) return
        val track = tracks[sound] ?: return
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() {
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    private fun render(sound: Sound): ShortArray = when (sound) {
        Sound.CHIRP -> sweep(900.0, 1700.0, 0.09)
        Sound.DOUBLE_CHIRP -> sweep(1000.0, 1800.0, 0.07) + silence(0.04) + sweep(1200.0, 2100.0, 0.07)
        Sound.HAPPY -> sweep(800.0, 1200.0, 0.06) + sweep(1200.0, 1600.0, 0.06) + sweep(1600.0, 2200.0, 0.08)
        Sound.CURIOUS -> sweep(600.0, 560.0, 0.08, volume = 0.25) + sweep(700.0, 1300.0, 0.12, volume = 0.28)
        Sound.HMM -> sweep(520.0, 430.0, 0.18, volume = 0.25)
        Sound.THINK -> sweep(660.0, 660.0, 0.06, volume = 0.18) + silence(0.07) +
            sweep(880.0, 880.0, 0.06, volume = 0.18) + silence(0.07) + sweep(740.0, 740.0, 0.06, volume = 0.18)
        Sound.LISTEN -> sweep(1100.0, 1100.0, 0.05, volume = 0.3) + silence(0.03) + sweep(1650.0, 1650.0, 0.07, volume = 0.3)
        Sound.GLITCH -> noise(0.05) + silence(0.02) + noise(0.04)
        Sound.ERROR -> sweep(440.0, 300.0, 0.12, volume = 0.3, harmonics = 0.45) + silence(0.04) +
            sweep(330.0, 220.0, 0.16, volume = 0.3, harmonics = 0.45)
        Sound.SLEEP -> sweep(900.0, 350.0, 0.35, volume = 0.2)
    }

    private fun silence(seconds: Double) = ShortArray((sampleRate * seconds).toInt())

    private fun sweep(
        fromHz: Double, toHz: Double, seconds: Double,
        volume: Double = 0.35, harmonics: Double = 0.15,
    ): ShortArray {
        val n = (sampleRate * seconds).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        val attack = (n * 0.08).toInt().coerceAtLeast(1)
        val release = (n * 0.35).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = fromHz + (toHz - fromHz) * t
            phase += 2 * PI * freq / sampleRate
            val env = min(1.0, min(i.toDouble() / attack, (n - i).toDouble() / release))
            // Sine plus some 3rd harmonic for a soft "electronic" timbre.
            val s = sin(phase) * (1 - harmonics) + sin(phase * 3) * harmonics
            out[i] = (s * env * volume * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Bit-crushed noise burst for the "glitch" sound. */
    private fun noise(seconds: Double, volume: Double = 0.18): ShortArray {
        val n = (sampleRate * seconds).toInt()
        val out = ShortArray(n)
        val rnd = Random(42)
        var held = 0.0
        for (i in 0 until n) {
            if (i % 6 == 0) held = rnd.nextDouble(-1.0, 1.0)
            val env = min(1.0, (n - i).toDouble() / (n * 0.5))
            out[i] = (held * env * volume * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }
}

/**
 * "No excessive sound" rule from the spec: at least [minGapMs] between sounds and at most
 * [maxPerWindow] sounds in any rolling [windowMs]. Pure Kotlin, unit-tested.
 */
class SoundLimiter(
    private val minGapMs: Long = 200,
    private val maxPerWindow: Int = 10,
    private val windowMs: Long = 60_000,
) {
    private val played = ArrayDeque<Long>()

    fun allow(now: Long): Boolean {
        while (played.isNotEmpty() && now - played.first() >= windowMs) played.removeFirst()
        val last = played.lastOrNull()
        if (last != null && now - last < minGapMs) return false
        if (played.size >= maxPerWindow) return false
        played.addLast(now)
        return true
    }
}
