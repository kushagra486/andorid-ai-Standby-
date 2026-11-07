package com.nova.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.nova.MainActivity
import com.nova.core.DataHub
import com.nova.integrations.Net
import com.nova.storage.Prefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Optional local "Hey NØVA" wake word (spec Stage 6/11) using Vosk keyword spotting with a
 * tiny grammar — fully offline, audio never leaves the phone. Runs as a foreground service
 * with a visible notification while listening. Off by default; touch-to-talk always works.
 */
object WakeWord {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private val main = Handler(Looper.getMainLooper())

    var onWake: (() -> Unit)? = null
    var status = "Off"
        internal set
    var detections = 0
        internal set
    internal var startedAt = 0L
    internal var cpuAtStart = 0L
    internal var batteryAtStart = -1
    private var paused = false

    fun modelDir(context: Context) = File(context.filesDir, "vosk-model")
    fun modelReady(context: Context) = File(modelDir(context), "am").exists() || File(modelDir(context), "conf").exists()

    /** Pure detection rule (unit-tested). Sensitivity 1 strict … 3 loose. */
    fun isWake(resultJson: String, partial: Boolean, sensitivity: Int): Boolean {
        val text = runCatching { JSONObject(resultJson).let { it.optString(if (partial) "partial" else "text") } }.getOrDefault("").lowercase()
        if (text.isBlank()) return false
        return when (sensitivity) {
            1 -> !partial && text.contains("hey nova")
            2 -> text.contains("hey nova") || (!partial && text.trim() == "nova")
            else -> text.contains("nova")
        }
    }

    fun benchmark(): String {
        if (startedAt == 0L) return "Not running"
        val mins = (SystemClock.elapsedRealtime() - startedAt) / 60_000.0
        val cpu = (Process.getElapsedCpuTime() - cpuAtStart) / 1000.0
        val cpuPct = if (mins > 0) cpu / (mins * 60) * 100 else 0.0
        val batt = DataHub.battery.percent
        val drop = if (batteryAtStart >= 0 && batt >= 0) batteryAtStart - batt else 0
        return String.format("%.0f min · app CPU %.1f%% · battery −%d%% · %d wakes", mins, cpuPct, drop, detections)
    }

    fun start(context: Context) {
        if (!modelReady(context)) { status = "Download the voice model first"; return }
        val i = Intent(context, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, WakeWordService::class.java))
        status = "Off"; startedAt = 0
    }

    /** Release the mic while touch-to-talk uses it, then resume. */
    fun pause(paused: Boolean) { this.paused = paused; WakeWordService.instance?.setPaused(paused) }

    fun downloadModel(context: Context, progress: (String) -> Unit, done: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        Net.bg.execute {
            val r = runCatching {
                val target = modelDir(app); val tmp = File(app.filesDir, "vosk-tmp")
                tmp.deleteRecursively(); tmp.mkdirs()
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                val total = conn.contentLength.toLong()
                var read = 0L; var lastReport = 0L
                ZipInputStream(CountingStream(conn.inputStream) { n ->
                    read = n
                    if (n - lastReport > 1_000_000) { lastReport = n; main.post { progress(if (total > 0) "Downloading… ${n * 100 / total}%" else "Downloading… ${n / 1_000_000} MB") } }
                }).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/')          // strip the top folder
                        if (rel.isNotBlank() && !rel.contains("..")) {
                            val f = File(tmp, rel)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                        }
                        e = zip.nextEntry
                    }
                }
                target.deleteRecursively()
                if (!tmp.renameTo(target)) throw Exception("Couldn't save the model")
                Unit
            }
            main.post { done(r) }
        }
    }

    private class CountingStream(private val inner: java.io.InputStream, private val onCount: (Long) -> Unit) : java.io.InputStream() {
        private var n = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) { n++; onCount(n) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) { n += it; onCount(n) } }
        override fun close() = inner.close()
    }
}

class WakeWordService : Service(), RecognitionListener {
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var lastWake = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        instance = this
        startInForeground()
        WakeWord.status = "Loading voice model…"
        Net.bg.execute {
            val r = runCatching { Model(WakeWord.modelDir(this).absolutePath) }
            main.post {
                r.onSuccess { model = it; begin() }
                    .onFailure { WakeWord.status = "Model failed to load: ${it.message}"; stopSelf() }
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("nova_wake", "Wake word", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "nova_wake") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NØVA is listening for “Hey NØVA”")
            .setContentText("Processed on this phone only. Tap to open.")
            .setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(42, n)
    }

    private fun begin() {
        val m = model ?: return
        runCatching {
            val rec = Recognizer(m, 16000f, "[\"hey nova\", \"nova\", \"[unk]\"]")
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            WakeWord.status = "Listening for “Hey NØVA”"
            WakeWord.startedAt = SystemClock.elapsedRealtime()
            WakeWord.cpuAtStart = Process.getElapsedCpuTime()
            WakeWord.batteryAtStart = DataHub.battery.percent
        }.onFailure { WakeWord.status = "Microphone unavailable: ${it.message}"; stopSelf() }
    }

    fun setPaused(p: Boolean) { runCatching { speech?.setPause(p) }; WakeWord.status = if (p) "Paused while you talk" else "Listening for “Hey NØVA”" }

    private fun check(json: String?, partial: Boolean) {
        if (json == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake < 3_000) return                       // cooldown
        if (WakeWord.isWake(json, partial, Prefs.wakeSensitivity)) {
            lastWake = now
            WakeWord.detections++
            val cb = WakeWord.onWake
            if (cb != null) cb() else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("wake", true))
        }
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, true)
    override fun onResult(hypothesis: String?) = check(hypothesis, false)
    override fun onFinalResult(hypothesis: String?) = check(hypothesis, false)
    override fun onError(exception: Exception?) { WakeWord.status = "Wake word error: ${exception?.message}" }
    override fun onTimeout() {}

    override fun onDestroy() {
        runCatching { speech?.stop(); speech?.shutdown() }
        runCatching { model?.close() }
        if (instance === this) instance = null
        WakeWord.startedAt = 0
        super.onDestroy()
    }

    companion object { @Volatile var instance: WakeWordService? = null }
}
