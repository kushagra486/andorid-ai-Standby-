package com.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.View.MeasureSpec
import com.nova.audio.SoundFx
import com.nova.dashboard.DashboardView
import com.nova.pet.Expression
import com.nova.pet.PetView
import com.nova.settings.NightMode
import com.nova.settings.NightStyle
import com.nova.settings.NovaSettings
import com.nova.settings.SettingsView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Renders every screen at the ZenFone's 1280×720 landscape resolution into
 * app/build/screenshots/ so the UI can be reviewed without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
class ScreenshotTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun layout(v: View) {
        v.measure(MeasureSpec.makeMeasureSpec(1280, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY))
        v.layout(0, 0, 1280, 720)
    }

    private fun save(v: View, name: String) {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Draw several frames so the springs settle on the target expression. */
    private fun settle(v: View, ms: Long = 1_500) {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var t = 0L
        while (t < ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(33)); v.draw(c); t += 33 }
    }

    @Test
    fun petExpressions() {
        for (night in listOf(false, true)) {
            val pet = PetView(context, SoundFx())
            pet.apply(NovaSettings(nightMode = if (night) NightMode.ALWAYS else NightMode.OFF, nightStyle = NightStyle.RED))
            layout(pet)
            pet.resume()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))   // hide first-run hint
            val list = if (night) listOf(Expression.IDLE, Expression.SLEEPING) else Expression.entries
            for (e in list) {
                pet.brain.forced = e
                settle(pet, if (night) 3_000 else 1_500)
                save(pet, (if (night) "night_" else "pet_") + e.name.lowercase())
            }
            pet.pause()
        }
    }

    @Test
    fun clockAndSettings() {
        val clock = DashboardView(context)
        clock.apply(NovaSettings(nightMode = NightMode.OFF))
        layout(clock); save(clock, "clock_day")
        clock.apply(NovaSettings(nightMode = NightMode.ALWAYS, nightStyle = NightStyle.RED))
        settle(clock, 2_000); save(clock, "clock_night_red")

        val settings = SettingsView(context, "0.2.0")
        layout(settings); save(settings, "settings")
    }
}
