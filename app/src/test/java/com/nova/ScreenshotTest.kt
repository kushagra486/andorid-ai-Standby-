package com.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.View.MeasureSpec
import com.nova.core.BatteryInfo
import com.nova.core.BluetoothInfo
import com.nova.core.CalendarEvent
import com.nova.core.DataHub
import com.nova.core.DashboardPage
import com.nova.core.Family
import com.nova.core.MediaInfo
import com.nova.core.NotificationItem
import com.nova.core.Reminder
import com.nova.core.StandbyHost
import com.nova.core.TimerInfo
import com.nova.core.WeatherInfo
import com.nova.core.WifiInfo
import com.nova.settings.AppearancePages
import com.nova.settings.SettingsPages
import com.nova.settings.SettingsScreen
import com.nova.storage.NightMode
import com.nova.storage.NightPalette
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.ThemePreset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Boots the real MainActivity on the JVM at the ZenFone's 1280×720 landscape size, fills
 * DataHub with sample data, and renders every StandBy family, overlay, Settings page and
 * theme to app/build/screenshots/. Doubles as an end-to-end smoke test of the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
class ScreenshotTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    fun data() {
        val now = System.currentTimeMillis()
        DataHub.battery = BatteryInfo(76, charging = true, plugged = true, tempC = 31f)
        DataHub.wifi = WifiInfo(enabled = true, connected = true, name = "Home-5G", signal = 2, online = true)
        DataHub.bluetooth = BluetoothInfo(available = true, enabled = true, connectedName = "JBL Flip 5", paired = listOf("JBL Flip 5", "Fire TV Stick", "Boat Rockerz"))
        DataHub.weather = WeatherInfo("Pune", 27.4, 2, true, 31.0, 19.0, 9.0, now, listOf(22 to 26.0, 23 to 25.0, 0 to 24.0, 1 to 23.0, 2 to 22.0, 3 to 21.0))
        DataHub.weatherStatus = ""
        DataHub.events = listOf(
            CalendarEvent("Design review", now + 40 * 60_000, now + 100 * 60_000, false, "Zoom"),
            CalendarEvent("Gym", now + 4 * 3600_000, now + 5 * 3600_000, false, ""),
            CalendarEvent("Mom's birthday", now + 20 * 3600_000, now + 44 * 3600_000, true, ""),
        )
        DataHub.calendarStatus = ""
        val art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, Color.rgb(255, 80, 140), Color.rgb(40, 60, 220), Shader.TileMode.CLAMP) })
        }
        DataHub.media = MediaInfo("Spotify", "com.spotify.music", "Midnight City", "M83", "Hurry Up, We're Dreaming", true, 95_000, 243_000, now, art)
        DataHub.mediaStatus = ""
        DataHub.timers = listOf(TimerInfo(1, "Tea", now + 3 * 60_000 + 12_000, 5 * 60_000))
        DataHub.reminders = listOf(Reminder(1, "Drink water", now + 30 * 60_000, false), Reminder(2, "Call Mom", now + 3 * 3600_000, false))
        DataHub.notifications = listOf(NotificationItem("WhatsApp", "Rahul", "Are we still on for 7?", now, "k1"), NotificationItem("Gmail", "Amazon", "Your order has shipped", now, "k2"))
    }

    private fun shot(v: View, name: String, settleMs: Long = 600) {
        var t = 0L
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        while (t < settleMs) { ShadowSystemClock.advanceBy(Duration.ofMillis(40)); ShadowLooper.idleMainLooper(); t += 40 }
        v.measure(MeasureSpec.makeMeasureSpec(1280, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY))
        v.layout(0, 0, 1280, 720)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T> field(o: Any, name: String): T { val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; @Suppress("UNCHECKED_CAST") return f.get(o) as T }

    @Test fun everyScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        // Stop live providers so the sample data below stays on screen.
        field<com.nova.integrations.SystemStatus>(a, "status").stop()
        data()
        Prefs.nightMode = NightMode.OFF
        Prefs.raw.edit().putBoolean("never", true).apply()
        Prefs.weatherPlace = "Pune"
        val root = a.window.decorView
        val host: StandbyHost = field(a, "host")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(9))

        // Families and variations.
        host.show(Family.PET); shot(root, "01_pet", 1_500)
        host.page(Family.PET).variation = 2; shot(root, "02_pet_status", 900)
        host.page(Family.PET).variation = 0
        for (i in 0 until host.page(Family.CLOCK).variationCount) {
            host.show(Family.CLOCK); host.page(Family.CLOCK).variation = i; shot(root, "1${i}_clock_$i", 500)
        }
        for (i in 0 until 3) { host.show(Family.DUAL); host.page(Family.DUAL).variation = i; shot(root, "2${i}_dual_$i", 500) }
        val dash = host.page(Family.DASHBOARD) as DashboardPage
        for (i in 0 until 4) { host.show(Family.DASHBOARD); dash.variation = i; shot(root, "3${i}_dashboard_$i", 500) }
        dash.variation = 0; dash.grid.setEditing(true); shot(root, "34_dashboard_edit", 500); dash.grid.setEditing(false)
        host.show(Family.PHOTOS); shot(root, "40_photos_empty", 500)
        host.show(Family.MUSIC); host.page(Family.MUSIC).variation = 0; shot(root, "50_music", 500)
        host.page(Family.MUSIC).variation = 1; shot(root, "51_music_dancing", 500); host.page(Family.MUSIC).variation = 0
        host.show(Family.ASSISTANT)
        val assistant: com.nova.voice.Assistant = field(a, "assistant")
        assistant.handle("set a timer for 5 minutes")
        assistant.handle("turn on the desk lamp")
        shot(root, "60_assistant_confirm", 500)
        assistant.confirm(false)

        // Overlays.
        host.show(Family.PET)
        a.javaClass.getDeclaredMethod("showMenu").apply { isAccessible = true }.invoke(a)
        shot(root, "70_quick_menu", 500)
        field<com.nova.core.QuickMenu>(a, "menu").hide(); ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); ShadowLooper.idleMainLooper()
        field<com.nova.core.QuickControls>(a, "controls").open(); shot(root, "71_quick_controls", 500)
        field<com.nova.core.QuickControls>(a, "controls").close(); ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); ShadowLooper.idleMainLooper()

        // Settings.
        a.javaClass.getDeclaredMethod("openSettings").apply { isAccessible = true }.invoke(a)
        val settings: SettingsScreen = field(a, "settings")
        shot(root, "80_settings", 500)
        settings.push(AppearancePages.appearance()); shot(root, "81_appearance", 500)
        settings.push(AppearancePages.colours()); shot(root, "82_colours", 500)
        settings.push(AppearancePages.picker(ColorRole.EYES)); shot(root, "83_picker", 500)
        settings.open(SettingsPages.layoutEditor(1)); shot(root, "84_layout_editor", 500)
        settings.open(SettingsPages.display(a)); shot(root, "85_display_night", 500)
        settings.open(SettingsPages.music(a)); shot(root, "86_music_settings", 500)
        settings.onDone?.invoke(); ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); ShadowLooper.idleMainLooper()

        // Themes, a custom colour, and night palettes.
        host.show(Family.DASHBOARD); dash.variation = 0
        for (t in listOf(ThemePreset.MIDNIGHT, ThemePreset.WARM_NIGHT, ThemePreset.FROST)) {
            Prefs.themePreset = t.name; shot(root, "9${t.ordinal}_theme_${t.name.lowercase()}", 400)
        }
        AppearancePages.setRole(ColorRole.EYES, Color.rgb(255, 95, 210))
        AppearancePages.setRole(ColorRole.CLOCK, Color.rgb(255, 214, 10))
        host.show(Family.PET); shot(root, "95_custom_pet", 1_200)
        host.show(Family.CLOCK); host.page(Family.CLOCK).variation = 0; shot(root, "96_custom_clock", 400)
        Prefs.themePreset = ThemePreset.NOVA_CYAN.name
        for (np in NightPalette.entries) {
            Prefs.nightPalette = np; Prefs.nightMode = NightMode.ON
            host.show(Family.CLOCK); shot(root, "97_night_${np.name.lowercase()}", 2_600)
            Prefs.nightMode = NightMode.OFF; ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        }
        Look.setNight(false, animate = false)
        controller.pause().stop().destroy()
    }
}
