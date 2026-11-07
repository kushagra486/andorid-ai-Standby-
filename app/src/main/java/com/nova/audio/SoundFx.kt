package com.nova.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short electronic chirps synthesised in code, so the app ships no audio files and
 * every sound is original. Each sound is rendered once into a static AudioTrack.
 */
class SoundFx {

    enum class Sound { CHIRP, DOUBLE_CHIRP, HAPPY, HMM, SLEEP }

    private val sampleRate = 22050
    private val tracks = HashMap<Sound, AudioTrack>()
    var muted = false

    init {
        Sound.entries.forEach { sound ->
            runCatching { tracks[sound] = buildTrack(render(sound)) }
        }
    }

    fun play(sound: Sound) {
        if (muted) return
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
        Sound.HMM -> sweep(520.0, 430.0, 0.18, volume = 0.25)
        Sound.SLEEP -> sweep(900.0, 350.0, 0.35, volume = 0.2)
    }

    private fun silence(seconds: Double) = ShortArray((sampleRate * seconds).toInt())

    private fun sweep(fromHz: Double, toHz: Double, seconds: Double, volume: Double = 0.35): ShortArray {
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
            // Sine plus a little 3rd harmonic for a soft "electronic" timbre.
            val s = sin(phase) * 0.85 + sin(phase * 3) * 0.15
            out[i] = (s * env * volume * Short.MAX_VALUE).toInt().toShort()
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
