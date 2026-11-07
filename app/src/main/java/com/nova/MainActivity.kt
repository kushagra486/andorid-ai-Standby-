package com.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.nova.actions.Action
import com.nova.actions.ActionType
import com.nova.audio.SoundFx
import com.nova.clock.ClockFaceView
import com.nova.core.AssistantPage
import com.nova.core.Clock
import com.nova.core.ClockPage
import com.nova.core.DashboardPage
import com.nova.core.DataHub
import com.nova.core.DualPage
import com.nova.core.Family
import com.nova.core.FamilyPage
import com.nova.core.MusicPage
import com.nova.core.NightController
import com.nova.core.PetInfo
import com.nova.core.PetPage
import com.nova.core.PhotoPage
import com.nova.core.QuickControls
import com.nova.core.QuickMenu
import com.nova.core.StandbyHost
import com.nova.home.SmartHome
import com.nova.integrations.Alarms
import com.nova.integrations.CalendarSource
import com.nova.integrations.MediaHub
import com.nova.integrations.NovaNotificationService
import com.nova.integrations.SystemStatus
import com.nova.integrations.Weather
import com.nova.music.MusicView
import com.nova.music.Spotify
import com.nova.pet.Expression
import com.nova.pet.PetView
import com.nova.photos.PhotoFrameView
import com.nova.settings.SettingsHost
import com.nova.settings.SettingsPages
import com.nova.settings.SettingsScreen
import com.nova.storage.ChargingView
import com.nova.storage.Intensity
import com.nova.storage.KeepAwake
import com.nova.storage.NightMode
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.voice.Assistant
import com.nova.voice.AssistantView
import com.nova.voice.Speaker
import com.nova.voice.VoiceInput
import com.nova.voice.WakeWord
import com.nova.widgets.Layout
import com.nova.widgets.LayoutStore
import com.nova.widgets.Tile
import com.nova.widgets.TileGridView
import com.nova.widgets.TileSize
import com.nova.widgets.WidgetRenderer
import com.nova.widgets.WidgetType
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * NØVA host activity: landscape, immersive. Hosts the StandBy families (Pixel Pet is the
 * launch and idle-return screen), quick menu, quick controls and Settings, and owns the
 * foreground-only providers, night/power behaviour and the allowlisted action executor.
 */
class MainActivity : Activity(), SettingsHost {

    private lateinit var sound: SoundFx
    private lateinit var pet: PetView
    private lateinit var host: StandbyHost
    private lateinit var menu: QuickMenu
    private lateinit var controls: QuickControls
    private lateinit var settings: SettingsScreen
    private lateinit var renderer: WidgetRenderer
    private lateinit var status: SystemStatus
    private lateinit var night: NightController
    private lateinit var voice: VoiceInput
    private lateinit var speaker: Speaker
    private lateinit var assistant: Assistant
    private var assistantView: AssistantView? = null
    private var photoView: PhotoFrameView? = null

    private var lastInteraction = SystemClock.uptimeMillis()
    private var pinned = false
    private var wasPlugged = false
    private var brightnessOverride = -1f
    private var lastHeatWarning = 0L
    private val handler get() = window.decorView.handler ?: android.os.Handler(mainLooper)

    private val idleCheck = object : Runnable {
        override fun run() { checkIdle(); window.decorView.postDelayed(this, 5_000) }
    }
    private val prefsListener = { onPrefsChanged() }
    private val dataListener = { onDataChanged() }

    // ---- Lifecycle ----------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Look.reload()

        sound = SoundFx()
        pet = PetView(this, sound)
        renderer = WidgetRenderer(this).apply { petMood = { pet.brain.current.label } }
        status = SystemStatus(this)
        night = NightController(this).apply { onChanged = { onNightChanged(it) } }
        voice = VoiceInput(this)
        speaker = Speaker(this)
        assistant = Assistant(this, voice, speaker, { pet.brain }, ::fact, ::execute).apply {
            onChange = { assistantView?.poke(); assistantView?.invalidate() }
            onListeningChanged = { on -> WakeWord.pause(on); if (on) sound.play(SoundFx.Sound.LISTEN, SystemClock.uptimeMillis()) }
            onNeedPermission = { requestPermission(android.Manifest.permission.RECORD_AUDIO) }
        }

        host = StandbyHost(this, ::createPage).apply {
            listener = object : StandbyHost.Listener {
                override fun onFamilyChanged(family: Family) { touch(); pinned = false }
                override fun onQuickControlsPull(progress: Float, release: Boolean) = controls.pull(progress, release)
                override fun onUserActivity() = touch()
            }
        }
        menu = QuickMenu(this, ::onMenu)
        controls = QuickControls(this).apply { host = controlsHost }
        settings = SettingsScreen(this).apply { visibility = View.GONE; onDone = { closeSettings() } }

        setContentView(FrameLayout(this).apply {
            addView(host); addView(controls); addView(menu); addView(settings)
        })

        pet.listener = object : PetView.Listener {
            override fun onPetLongPress() = showMenu()
            override fun onExpressionTestFinished() = openSettings()
        }

        Alarms.load()
        Alarms.onFired = { msg -> runOnUiThread { pet.brain.react(Expression.SURPRISED, SystemClock.uptimeMillis()); pet.brain.error(msg, SystemClock.uptimeMillis()); host.show(Family.PET) } }
        MediaHub.onPlayingChanged = { playing -> runOnUiThread { onMusicPlaying(playing) } }
        WakeWord.onWake = { runOnUiThread { touch(); host.show(Family.ASSISTANT); if (assistant.state != Assistant.State.LISTENING) assistant.listen() } }
        Weather.loadCache()

        host.show(Family.PET)
        applyPrefs()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        Prefs.addListener(prefsListener)
        DataHub.addListener(dataListener)
        status.start()
        night.start(); night.evaluate(animate = false)
        Weather.refresh()
        CalendarSource.refresh(this)
        if (!settings.isShown) host.resumeCurrent()
        if (Prefs.wakeWordEnabled) WakeWord.start(this)
        window.decorView.removeCallbacks(idleCheck); window.decorView.post(idleCheck)
        applyWindow()
    }

    override fun onPause() {
        host.pauseAll()
        status.stop(); night.stop()
        Prefs.removeListener(prefsListener)
        DataHub.removeListener(dataListener)
        window.decorView.removeCallbacks(idleCheck)
        assistant.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release(); speaker.shutdown()
        WakeWord.onWake = null; Alarms.onFired = null; MediaHub.onPlayingChanged = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) touch()
        return super.dispatchTouchEvent(ev)
    }

    private fun touch() { lastInteraction = SystemClock.uptimeMillis() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            menu.showing -> menu.hide()
            controls.showing -> controls.close()
            settings.isShown -> settings.pop()
            host.currentPage.onBack() -> Unit
            host.current != Family.PET -> host.show(Family.PET)
            else -> pet.brain.react(Expression.CURIOUS, SystemClock.uptimeMillis())   // stay as the desk display
        }
    }

    // ---- Pages --------------------------------------------------------------------------
    private fun createPage(f: Family): FamilyPage = when (f) {
        Family.PET -> PetPage(pet, ::compactInfo)
        Family.CLOCK -> ClockPage(ClockFaceView(this))
        Family.DUAL -> DualPage(TileGridView(this, renderer).apply { listener = gridListener })
        Family.DASHBOARD -> DashboardPage(TileGridView(this, renderer).apply { listener = gridListener })
        Family.PHOTOS -> PhotoPage(PhotoFrameView(this).apply { onPickFolder = { pickPhotoFolder() }; photoView = this })
        Family.MUSIC -> MusicPage(MusicView(this).apply {
            onChooseDestination = { chooseDestination() }
            onOpenPlayer = { openSpotifyApp() }
        })
        Family.ASSISTANT -> AssistantPage(AssistantView(this, assistant).apply { onType = { typeToAssistant() }; assistantView = this })
    }

    private fun compactInfo(info: PetInfo): String? = when (info) {
        PetInfo.NONE -> null
        PetInfo.TIME -> Clock.time(this) + Clock.amPm(this).let { if (it.isEmpty()) "" else " $it" }
        PetInfo.STATUS -> listOfNotNull(
            Clock.time(this),
            DataHub.weather?.let { "${it.tempC.roundToInt()}°" },
            DataHub.battery.percent.takeIf { it >= 0 }?.let { "$it%" + if (DataHub.battery.charging) " ⚡" else "" },
        ).joinToString("  ·  ")
        PetInfo.MUSIC -> DataHub.media?.let { "♪ ${it.title} — ${it.artist}" } ?: "♪ Nothing playing"
    }

    private val gridListener = object : TileGridView.Listener {
        override fun onWidgetTap(type: WidgetType, tile: Tile) = widgetTap(type)
        override fun onLayoutEdited(layout: Layout) { if (layout.id > 0) LayoutStore.save(layout) }
        override fun onAddWidget(layout: Layout) = pickWidget { w ->
            val l = LayoutStore.book.layouts.firstOrNull { it.id == layout.id } ?: layout
            val n = l.copy(tiles = l.tiles + Tile(LayoutStore.newId(), TileSize.SMALL, listOf(w)))
            LayoutStore.save(n); (host.page(Family.DASHBOARD) as DashboardPage).grid.layout = n
        }
        override fun onTileOptions(layout: Layout, tile: Tile) { openSettings(); settings.push(SettingsPages.layouts()); settings.push(SettingsPages.layoutEditor(layout.id)); settings.push(SettingsPages.tileEditor(layout.id, tile.id)) }
        override fun onLockedLongPress() = toast("This layout is locked — unlock it in Settings → Layouts")
    }

    private fun widgetTap(type: WidgetType) {
        when (type) {
            WidgetType.CLOCK, WidgetType.DATE -> host.show(Family.CLOCK)
            WidgetType.WEATHER -> { Weather.refresh(force = true); toast("Updating weather…") }
            WidgetType.MUSIC -> host.show(Family.MUSIC)
            WidgetType.PET -> host.show(Family.PET)
            WidgetType.WIFI -> openSystemScreen(Settings.ACTION_WIFI_SETTINGS)
            WidgetType.BLUETOOTH -> openSystemScreen(Settings.ACTION_BLUETOOTH_SETTINGS)
            WidgetType.TIMER -> timerTap()
            WidgetType.REMINDERS -> remindersDialog()
            WidgetType.CALENDAR -> if (!CalendarSource.granted(this)) requestPermission(android.Manifest.permission.READ_CALENDAR) else CalendarSource.refresh(this)
            WidgetType.NOTIFICATIONS -> if (!NovaNotificationService.enabled(this)) openNotificationAccess()
            WidgetType.BATTERY -> Unit
        }
    }

    // ---- Quick menu ---------------------------------------------------------------------
    private fun showMenu() {
        menu.items = listOf(
            QuickMenu.Item("clock", "◷", "Clocks"), QuickMenu.Item("dual", "◫", "Dual"),
            QuickMenu.Item("dashboard", "▦", "Dashboard"), QuickMenu.Item("photos", "▣", "Photos"),
            QuickMenu.Item("music", "♪", "Music"), QuickMenu.Item("talk", "●", "Talk"),
            QuickMenu.Item("edit", "✎", "Edit layout"),
            QuickMenu.Item("night", "☾", "Night: " + Prefs.nightMode.label.substringBefore(' ')),
            QuickMenu.Item("sleep", "z", "Sleep"), QuickMenu.Item("pin", "📌", if (pinned) "Unpin" else "Pin screen"),
            QuickMenu.Item("controls", "☀", "Controls"), QuickMenu.Item("settings", "⚙", "Settings"),
        )
        menu.show()
    }

    private fun onMenu(id: String) {
        when (id) {
            "clock" -> host.show(Family.CLOCK); "dual" -> host.show(Family.DUAL); "dashboard" -> host.show(Family.DASHBOARD)
            "photos" -> host.show(Family.PHOTOS); "music" -> host.show(Family.MUSIC)
            "talk" -> { host.show(Family.ASSISTANT); assistant.listen() }
            "edit" -> { host.show(Family.DASHBOARD); (host.page(Family.DASHBOARD) as DashboardPage).grid.setEditing(true) }
            "night" -> Prefs.nightMode = when (Prefs.nightMode) { NightMode.AUTO -> NightMode.ON; NightMode.ON -> NightMode.OFF; NightMode.OFF -> NightMode.AUTO }
            "sleep" -> execute(Action(ActionType.PET_SLEEP))
            "pin" -> { pinned = !pinned; toast(if (pinned) "Screen pinned — won't return to Pixel Pet" else "Unpinned") }
            "controls" -> controls.open()
            "settings" -> openSettings()
        }
    }

    private val controlsHost = object : QuickControls.Host {
        private val am get() = getSystemService(AUDIO_SERVICE) as AudioManager
        override fun brightness(): Int {
            val b = window.attributes.screenBrightness
            return if (b < 0) runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255 }.getOrDefault(50) else (b * 100).toInt()
        }
        override fun setBrightness(pct: Int) { brightnessOverride = (pct.coerceIn(1, 100) / 100f); applyWindow() }
        override fun volume(): Int = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        override fun setVolume(pct: Int) = am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100f).roundToInt(), 0)
        override fun toggles() = listOf(
            Triple("sound", "Sounds", Prefs.soundOn),
            Triple("night", "Night: " + Prefs.nightMode.label.substringBefore(' '), Look.isNight),
            Triple("awake", "Stay awake", Prefs.keepAwake == KeepAwake.ALWAYS),
            Triple("wake", "Hey NØVA", Prefs.wakeWordEnabled),
            Triple("pin", "Pin screen", pinned),
            Triple("wifi", "Wi-Fi…", false), Triple("bt", "Bluetooth…", false),
        )
        override fun onToggle(id: String) {
            when (id) {
                "sound" -> Prefs.soundOn = !Prefs.soundOn
                "night" -> onMenu("night")
                "awake" -> Prefs.keepAwake = if (Prefs.keepAwake == KeepAwake.ALWAYS) KeepAwake.SYSTEM else KeepAwake.ALWAYS
                "wake" -> if (WakeWord.modelReady(this@MainActivity)) { Prefs.wakeWordEnabled = !Prefs.wakeWordEnabled; wakeWordChanged() } else toast("Download the voice model in Settings → Wake word")
                "pin" -> pinned = !pinned
                "wifi" -> openSystemScreen(Settings.ACTION_WIFI_SETTINGS)
                "bt" -> openSystemScreen(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
        }
    }

    // ---- Settings -----------------------------------------------------------------------
    private fun openSettings() {
        menu.hide(); controls.close()
        host.pauseAll()
        settings.open(SettingsPages.root(this))
        settings.visibility = View.VISIBLE; settings.alpha = 0f
        settings.animate().alpha(1f).setDuration(180).start()
        touch()
    }

    private fun closeSettings() {
        settings.animate().alpha(0f).setDuration(160).withEndAction { settings.visibility = View.GONE }.start()
        host.resumeCurrent()
        (host.page(Family.DASHBOARD) as? DashboardPage)?.reload()
    }

    private fun onPrefsChanged() {
        Look.reload()
        applyPrefs()
        host.invalidateAll()
    }

    private fun StandbyHost.invalidateAll() {
        for (i in 0 until childCount) getChildAt(i).invalidate()
        if (current == Family.DASHBOARD && !settings.isShown) (page(Family.DASHBOARD) as DashboardPage).reload()
    }

    private fun applyPrefs() {
        sound.enabled = Prefs.soundOn
        sound.volume = Prefs.volume.gain
        pet.applyPrefs()
        night.start(); night.evaluate()
        applyWindow()
    }

    /** Keep-awake, brightness (night dimming, manual override, heat safeguard). */
    private fun applyWindow() {
        val plugged = DataHub.battery.plugged
        val keep = when (Prefs.keepAwake) { KeepAwake.ALWAYS -> true; KeepAwake.CHARGING -> plugged; KeepAwake.SYSTEM -> false }
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hot = !DataHub.battery.tempC.isNaN() && DataHub.battery.tempC >= 45f
        var b = when {
            brightnessOverride > 0 -> brightnessOverride
            Look.isNight -> Prefs.raw.getInt("nightBrightness", 8) / 100f
            else -> -1f
        }
        if (hot) b = if (b < 0) 0.3f else minOf(b, 0.3f)
        val lp = window.attributes
        if (lp.screenBrightness != b) { lp.screenBrightness = b; window.attributes = lp }
    }

    private fun onNightChanged(isNight: Boolean) {
        brightnessOverride = -1f
        applyWindow()
        if (isNight && Prefs.nightKeepClock && host.current == Family.PET) host.show(Family.CLOCK)
        host.invalidateAll()
    }

    private fun onDataChanged() {
        val b = DataHub.battery
        if (b.plugged && !wasPlugged && Prefs.chargingView != ChargingView.PET) {
            host.show(if (Prefs.chargingView == ChargingView.CLOCK) Family.CLOCK else Family.PHOTOS)
        }
        if (b.plugged != wasPlugged) { wasPlugged = b.plugged; applyWindow() }
        if (!b.tempC.isNaN() && b.tempC >= 45f && SystemClock.uptimeMillis() - lastHeatWarning > 10 * 60_000L) {
            lastHeatWarning = SystemClock.uptimeMillis()
            pet.brain.error("Phone is warm (${b.tempC.roundToInt()}°C) — dimming to cool down", SystemClock.uptimeMillis())
            applyWindow()
        }
        // Context-based layout switching.
        val book = LayoutStore.book
        book.forContext(Calendar.getInstance(), DataHub.media?.playing == true, Look.isNight)?.let { l ->
            if (l.id != book.activeId && host.current != Family.DASHBOARD) LayoutStore.book = book.copy(activeId = l.id)
        }
    }

    private fun onMusicPlaying(playing: Boolean) {
        if (Prefs.danceWithMusic) pet.brain.setActivity(if (playing) Expression.DANCING else null)
        onDataChanged()
    }

    /** Idle return to the Pixel Pet (or the charging view) unless pinned or busy. */
    private fun checkIdle() {
        val limit = Prefs.idleReturnSec
        if (limit <= 0 || pinned || settings.isShown || menu.showing) return
        if (assistant.state != Assistant.State.IDLE) { touch(); return }
        if (host.currentPage.capturesGestures) return
        if (SystemClock.uptimeMillis() - lastInteraction < limit * 1000L) return
        val home = if (DataHub.battery.plugged) when (Prefs.chargingView) {
            ChargingView.CLOCK -> Family.CLOCK; ChargingView.PHOTOS -> Family.PHOTOS; ChargingView.PET -> Family.PET
        } else if (Look.isNight && Prefs.nightKeepClock) Family.CLOCK else Family.PET
        if (host.current != home) host.show(home)
    }

    // ---- Assistant facts & actions ------------------------------------------------------
    private fun fact(key: String): String = when (key) {
        "time" -> "It's ${Clock.time(this)} ${Clock.amPm(this)}".trim() + "."
        "date" -> "Today is ${Clock.date()}."
        "weather" -> DataHub.weather?.let { "${Weather.describe(it.code)}, ${it.tempC.roundToInt()} degrees in ${it.place}. High ${it.highC.roundToInt()}, low ${it.lowC.roundToInt()}." }
            ?: DataHub.weatherStatus
        "battery" -> DataHub.battery.let { if (it.percent >= 0) "Battery is at ${it.percent} percent" + (if (it.charging) " and charging." else ".") else "I can't read the battery." }
        "calendar" -> DataHub.events.firstOrNull { it.end > System.currentTimeMillis() }?.let {
            "Next: ${it.title} at ${android.text.format.DateFormat.format(if (Clock.is24(this)) "HH:mm" else "h:mm a", it.start)}."
        } ?: DataHub.calendarStatus.ifBlank { "Nothing else on your calendar." }
        else -> ""
    }

    /** The only place actions are executed — always after ActionValidator. */
    private fun execute(a: Action): Result<String> = runCatching {
        val v = com.nova.actions.ActionValidator.validate(a).getOrThrow()
        when (v.type) {
            ActionType.SHOW_SCREEN -> { host.show(Family.valueOf(v.arg("screen").uppercase())); "" }
            ActionType.PET_SLEEP -> { host.show(Family.PET); pet.brain.forced = Expression.SLEEPING; window.decorView.postDelayed({ pet.brain.forced = null }, 60_000); "Good night." }
            ActionType.PET_WAKE -> { pet.brain.forced = null; pet.touched(); "I'm awake!" }
            ActionType.NIGHT_MODE -> { Prefs.nightMode = when (v.arg("mode")) { "on" -> NightMode.ON; "off" -> NightMode.OFF; else -> NightMode.AUTO }; "Night mode ${v.arg("mode")}." }
            ActionType.SET_TIMER -> { val t = Alarms.startTimer(this, v.arg("minutes").toDouble(), v.arg("label")); "${t.label} started." }
            ActionType.CANCEL_TIMERS -> { DataHub.timers.forEach { Alarms.cancelTimer(this, it.id) }; "Timers cleared." }
            ActionType.ADD_REMINDER -> {
                val at = v.args["in_minutes"]?.let { System.currentTimeMillis() + (it.toDouble() * 60_000).toLong() } ?: run {
                    val (h, m) = v.arg("at").split(":").map { it.toInt() }
                    Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
                        if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                }
                Alarms.addReminder(this, v.arg("text"), at)
                "Reminder set for ${android.text.format.DateFormat.format(if (Clock.is24(this)) "HH:mm" else "h:mm a", at)}."
            }
            ActionType.MEDIA -> {
                if (!MediaHub.available) throw Exception("No music app is playing — open Spotify first" + if (!NovaNotificationService.enabled(this)) " and allow notification access" else "")
                when (v.arg("command")) { "play" -> MediaHub.play(); "pause" -> MediaHub.pause(); "next" -> MediaHub.next(); "previous" -> MediaHub.previous(); else -> MediaHub.playPause() }
                ""
            }
            ActionType.SPOTIFY_PLAY -> {
                if (!Spotify.signedIn) { openSpotifyApp(); throw Exception("Sign in to Spotify in Settings → Music to play by name") }
                Spotify.playSearch(v.arg("query"), v.arg("kind")) { r -> r.onSuccess { toast(it) }.onFailure { pet.brain.error(it.message ?: "Spotify error", SystemClock.uptimeMillis()) } }
                "Looking for ${v.arg("query")}…"
            }
            ActionType.SPOTIFY_TRANSFER -> {
                if (!Spotify.signedIn) throw Exception("Sign in to Spotify in Settings → Music first")
                Spotify.transferByName(v.arg("device")) { r ->
                    r.onSuccess { DataHub.spotifyDevice = it.removePrefix("Playing on "); DataHub.changed(); toast(it) }
                        .onFailure { pet.brain.error(it.message ?: "Transfer failed", SystemClock.uptimeMillis()) }
                }
                "Moving playback…"
            }
            ActionType.SET_VOLUME -> { controlsHost.setVolume(v.arg("level").toInt()); "Volume ${v.arg("level")}." }
            ActionType.SET_BRIGHTNESS -> { controlsHost.setBrightness(v.arg("level").toInt()); "Brightness ${v.arg("level")}." }
            ActionType.SMART_HOME -> {
                val d = SmartHome.find(v.arg("device")) ?: throw Exception("I don't know a device called “${v.arg("device")}” (add it in Settings → Smart home)")
                SmartHome.command(d, v.arg("command")) { r -> r.onSuccess { toast(it) }.onFailure { pet.brain.error(it.message ?: "Device error", SystemClock.uptimeMillis()) } }
                "Okay."
            }
            ActionType.OPEN_SETTINGS -> { openSettings(); "" }
        }
    }

    // ---- Dialog helpers -----------------------------------------------------------------
    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun pickWidget(done: (WidgetType) -> Unit) {
        val types = WidgetType.entries
        dialog().setTitle("Add widget").setItems(types.map { it.label }.toTypedArray()) { _, i -> done(types[i]) }.show()
    }

    private fun timerTap() {
        val now = System.currentTimeMillis()
        val t = DataHub.timers.minByOrNull { it.endsAt }
        when {
            t == null -> dialog().setTitle("Start a timer").setItems(arrayOf("1 min", "5 min", "10 min", "15 min", "25 min (focus)", "60 min")) { _, i ->
                Alarms.startTimer(this, listOf(1.0, 5.0, 10.0, 15.0, 25.0, 60.0)[i])
            }.show()
            t.endsAt <= now -> Alarms.clearFinished(this)
            else -> dialog().setTitle(t.label).setMessage("Cancel this timer?").setPositiveButton("Cancel timer") { _, _ -> Alarms.cancelTimer(this, t.id) }.setNegativeButton("Keep", null).show()
        }
    }

    private fun remindersDialog() {
        val list = DataHub.reminders.filter { !it.done }.sortedBy { it.at }
        val labels = list.map { "✓  ${it.text}  ·  " + android.text.format.DateFormat.format("EEE HH:mm", it.at) } + "+  Add reminder…"
        dialog().setTitle("Reminders (tap to complete)").setItems(labels.toTypedArray()) { _, i ->
            if (i < list.size) Alarms.completeReminder(this, list[i].id) else {
                val input = EditText(this).apply { hint = "e.g. Call mom in 30 minutes" }
                dialog().setTitle("New reminder").setView(input).setPositiveButton("Add") { _, _ ->
                    assistant.handle("remind me to " + input.text.toString().removePrefix("remind me to "))
                }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun typeToAssistant() {
        val input = EditText(this).apply { hint = "Ask or tell NØVA something"; setSingleLine(true) }
        dialog().setTitle("Type to NØVA").setView(input).setPositiveButton("Send") { _, _ ->
            val t = input.text.toString().trim(); if (t.isNotEmpty()) assistant.handle(t)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseDestination() {
        val local = listOf("This phone (${DataHub.audioRoute})", "Bluetooth speaker/headphones…")
        if (!Spotify.signedIn) {
            dialog().setTitle("Play on").setItems((local + "Spotify Connect (sign in first)").toTypedArray()) { _, i ->
                when (i) { 1 -> openSystemScreen(Settings.ACTION_BLUETOOTH_SETTINGS); 2 -> { openSettings(); settings.push(SettingsPages.music(this)) } }
            }.show()
            return
        }
        toast("Finding Spotify devices…")
        Spotify.devices { r ->
            r.onSuccess { devices ->
                val names = local + devices.map { (if (it.active) "● " else "") + "${it.name} (${it.type})" }
                dialog().setTitle("Play on").setItems(names.toTypedArray()) { _, i ->
                    when {
                        i == 0 -> { DataHub.spotifyDevice = ""; DataHub.changed() }
                        i == 1 -> openSystemScreen(Settings.ACTION_BLUETOOTH_SETTINGS)
                        else -> {
                            val d = devices[i - 2]
                            assistant.handle("move the music to ${d.name}")
                        }
                    }
                }.show()
            }.onFailure { e ->
                dialog().setTitle("Spotify").setMessage("${e.message}\n\nManual fallback: open Spotify, tap the devices icon and choose your TV or speaker.")
                    .setPositiveButton("Open Spotify") { _, _ -> openSpotifyApp() }.setNegativeButton("Close", null).show()
            }
        }
    }

    private fun openSpotifyApp() {
        val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i != null) startActivity(i) else toast("Spotify isn't installed")
    }

    // ---- Intents, permissions, pickers --------------------------------------------------
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.toString().startsWith(Spotify.REDIRECT)) {
            Spotify.handleRedirect(data) { r ->
                r.onSuccess { toast("Spotify connected"); Prefs.notifyChanged() }.onFailure { toast("Spotify sign-in failed: ${it.message}") }
            }
            intent.data = null
        }
        if (intent?.getBooleanExtra("wake", false) == true) {
            intent.removeExtra("wake")
            window.decorView.post { host.show(Family.ASSISTANT); assistant.listen() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Prefs.photoTreeUri.takeIf { it.isNotBlank() && it != uri.toString() }?.let { old ->
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            Prefs.photoTreeUri = uri.toString()
            photoView?.rescan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (permissions.firstOrNull()) {
            android.Manifest.permission.READ_CALENDAR -> CalendarSource.refresh(this)
            android.Manifest.permission.RECORD_AUDIO -> if (!granted) toast("Without the microphone, use “Type instead”")
            "android.permission.BLUETOOTH_CONNECT" -> status.refresh()
        }
        Prefs.notifyChanged()
    }

    override fun pickPhotoFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_FOLDER)
        } catch (e: ActivityNotFoundException) { toast("No folder picker available on this phone") }
    }

    override fun requestPermission(permission: String) = requestPermissions(arrayOf(permission), REQ_PERMISSION)
    override fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    override fun openNotificationAccess() = openSystemScreen("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

    override fun openSystemScreen(action: String) {
        try { startActivity(Intent(action).apply { if (action.contains("EXACT_ALARM")) data = Uri.parse("package:$packageName") }) }
        catch (e: Exception) { toast("This setting isn't available on this phone") }
    }

    override fun hasLightSensor() = (getSystemService(SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_LIGHT) != null
    override fun testLightSensor(done: (String, Boolean) -> Unit) = night.testSensor(done = done)
    override fun startExpressionTest() { closeSettings(); host.show(Family.PET); pet.startExpressionTest() }

    override fun spotifySignIn() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Spotify.authUrl())) } catch (e: Exception) { toast("No browser available") }
    }

    override fun wakeWordChanged() { if (Prefs.wakeWordEnabled) WakeWord.start(this) else WakeWord.stop(this) }
    override fun speakerStatus() = speaker.status
    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun playTestSound() { sound.enabled = Prefs.soundOn; sound.volume = Prefs.volume.gain; sound.play(SoundFx.Sound.HAPPY, SystemClock.uptimeMillis(), force = true) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) touch()
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION") // WindowInsetsController is API 30+; the ZenFone runs API 24.
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private companion object {
        const val REQ_FOLDER = 11
        const val REQ_PERMISSION = 12
    }
}
