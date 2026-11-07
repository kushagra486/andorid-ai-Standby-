package com.nova

import android.app.Application
import com.nova.storage.Prefs
import com.nova.ui.Look
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App entry: settings, theme, and a crash recorder (kept on-device for Diagnostics). */
class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Look.reload()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                // commit() — the process is about to die, apply() might not flush.
                Prefs.raw.edit().putString("lastCrash", "$stamp · v${BuildConfig.VERSION_NAME}\n" + sw.toString().take(3_000)).commit()
            }
            previous?.uncaughtException(t, e)
        }
    }
}
