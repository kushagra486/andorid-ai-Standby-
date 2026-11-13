package com.nova.settings

import android.Manifest
import com.nova.BuildConfig
import com.nova.ai.AiClient
import com.nova.core.DataHub
import com.nova.core.Family
import com.nova.home.SmartDevice
import com.nova.home.SmartHome
import com.nova.integrations.CalendarSource
import com.nova.integrations.NovaNotificationService
import com.nova.integrations.Weather
import com.nova.music.Spotify
import com.nova.storage.ChargingView
import com.nova.storage.ClockFormat
import com.nova.storage.Intensity
import com.nova.storage.KeepAwake
import com.nova.storage.NightMode
import com.nova.storage.NightPalette
import com.nova.storage.Prefs
import com.nova.storage.TempUnit
import com.nova.storage.Volume
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.ThemePreset
import com.nova.voice.WakeWord
import com.nova.widgets.Layout
import com.nova.widgets.LayoutBook
import com.nova.widgets.LayoutContext
import com.nova.widgets.LayoutStore
import com.nova.widgets.Tile
import com.nova.widgets.TileSize
import com.nova.widgets.WidgetType

/** Things only the activity can do (pickers, permissions, system screens). */
interface SettingsHost {
    fun pickPhotoFolder()
    fun requestPermission(permission: String)
    fun hasPermission(permission: String): Boolean
    fun openNotificationAccess()
    fun openSystemScreen(action: String)
    fun hasLightSensor(): Boolean
    fun testLightSensor(done: (String, Boolean) -> Unit)
    fun startExpressionTest()
    fun spotifySignIn()
    fun wakeWordChanged()
    fun speakerStatus(): String
    fun toast(message: String)
    fun playTestSound()
}

object SettingsPages {

    fun root(host: SettingsHost): SettingsPage = SettingsPage("Settings") {
        section("Colour theme", footer = "Tap a theme to apply it. Customise colours lets you pick your own colour for every part of NØVA.") {
            custom(ThemeStripView(screen.context), screen.dp(150))
            nav("Customise colours", if (Look.preset == ThemePreset.CUSTOM) "Custom" else "") { AppearancePages.colours() }
        }
        section {
            nav("Appearance", Look.preset.label) { AppearancePages.appearance() }
            nav("Display & night", Prefs.nightMode.label) { display(host) }
            nav("Sound", if (Prefs.soundOn) Prefs.volume.label else "Off") { sound(host) }
            nav("Clock", Prefs.clockFormat.label) { clock() }
        }
        section("StandBy") {
            nav("Pages") { pages() }
            nav("Layouts & widgets", LayoutStore.book.active.name) { layouts() }
            nav("Photo frame", if (Prefs.photoTreeUri.isBlank()) "Not set" else "On") { photos(host) }
            nav("Weather", Prefs.weatherPlace.ifBlank { "Not set" }) { weather() }
        }
        section("Assistant") {
            nav("Voice & speech") { voice(host) }
            nav("Cloud AI", if (AiClient.configured) "On" else "Off") { ai() }
            nav("Wake word", if (Prefs.wakeWordEnabled) "On" else "Off") { wake(host) }
        }
        section("Connections") {
            nav("Music & Spotify", if (Spotify.signedIn) "Signed in" else "") { music(host) }
            nav("Notifications & calendar") { access(host) }
            nav("Smart home", "${SmartHome.devices().size} devices") { smartHome() }
        }
        section("Pixel Pet") {
            choice("Animation", Intensity.entries.toList(), Prefs.intensity, { it.label }) { Prefs.intensity = it }
            action("Test all expressions") { host.startExpressionTest() }
        }
        section("About") {
            info("Version", BuildConfig.VERSION_NAME)
            nav("Diagnostics") { diagnostics(host) }
        }
    }

    // ---- Display & night ----------------------------------------------------------------
    fun display(host: SettingsHost) = SettingsPage("Display & night") {
        section("Night mode", footer = "Automatic follows the schedule, and — if enabled after a successful sensor test — also a dark room. " +
            "The ZenFone has an LCD, so night mode dims the whole screen rather than switching pixels off.") {
            choice("Mode", NightMode.entries.toList(), Prefs.nightMode, { it.label }) { Prefs.nightMode = it }
            choice("Starts", (0..23).toList(), Prefs.nightStartHour, { String.format("%02d:00", it) }) { Prefs.nightStartHour = it }
            choice("Ends", (0..23).toList(), Prefs.nightEndHour, { String.format("%02d:00", it) }) { Prefs.nightEndHour = it }
            choice("Night palette", NightPalette.entries.toList(), Prefs.nightPalette, { it.label }) { Prefs.nightPalette = it }
            slider("Night brightness", 1, 60, Prefs.raw.getInt("nightBrightness", 8), { "$it%" }) { Prefs.raw.edit().putInt("nightBrightness", it).apply(); Prefs.notifyChanged() }
            toggle("Keep clock visible at night", Prefs.nightKeepClock) { Prefs.nightKeepClock = it }
        }
        section("Ambient light", footer = if (host.hasLightSensor()) "Cover and uncover the sensor (near the earpiece) during the 8-second test." else "This phone reports no light sensor; the schedule is used.") {
            info("Sensor", Prefs.lightSensorStatus)
            if (host.hasLightSensor()) {
                action("Test light sensor") {
                    host.toast("Testing… cover and uncover the top of the phone")
                    host.testLightSensor { msg, ok -> Prefs.lightSensorStatus = msg; if (!ok) Prefs.useLightSensor = false }
                }
                if (Prefs.lightSensorStatus.startsWith("Working")) toggle("Use light sensor", Prefs.useLightSensor) { Prefs.useLightSensor = it }
            }
        }
        section("Screen", footer = "StandBy-style: when you plug in the charger NØVA can switch to the clock or photos. Battery-temperature safeguards always apply.") {
            choice("Keep screen on", KeepAwake.entries.toList(), Prefs.keepAwake, { it.label }) { Prefs.keepAwake = it }
            choice("When charging, show", ChargingView.entries.toList(), Prefs.chargingView, { it.label }) { Prefs.chargingView = it }
            choice("Return to Pixel Pet after", listOf(30, 60, 120, 300, 600, 0), Prefs.idleReturnSec, { if (it == 0) "Never" else if (it < 60) "$it s" else "${it / 60} min" }) { Prefs.idleReturnSec = it }
            toggle("Larger text in Settings", Prefs.largeText) { Prefs.largeText = it }
        }
    }

    fun sound(host: SettingsHost) = SettingsPage("Sound") {
        section(footer = "NØVA never makes more than one sound every 0.2 s or more than 10 sounds a minute, and stays silent at night.") {
            toggle("Sounds", Prefs.soundOn) { Prefs.soundOn = it }
            choice("Volume", Volume.entries.toList(), Prefs.volume, { it.label }) { Prefs.volume = it; host.playTestSound() }
            action("Play a test chirp") { host.playTestSound() }
        }
    }

    fun clock() = SettingsPage("Clock") {
        section {
            choice("Format", ClockFormat.entries.toList(), Prefs.clockFormat, { it.label }) { Prefs.clockFormat = it }
            toggle("Show seconds (digital)", Prefs.showSeconds) { Prefs.showSeconds = it }
        }
        section(footer = "Clock faces: swipe up/down on the Clocks page. Colours of digits and hands are in Appearance → Customise colours.") {}
    }

    fun pages() = SettingsPage("Pages") {
        section(footer = "Choose which StandBy pages appear when you swipe left/right. Pixel Pet is always on and is the idle-return screen.") {
            val off = Prefs.disabledFamilies.split(',').filter { it.isNotBlank() }.toMutableSet()
            Family.entries.filter { it != Family.PET }.forEach { f ->
                toggle(f.label, f.name !in off) { on ->
                    if (on) off.remove(f.name) else off.add(f.name)
                    Prefs.disabledFamilies = off.joinToString(",")
                }
            }
        }
    }

    // ---- Layout editor (same saved state as direct on-screen editing) --------------------
    fun layouts(): SettingsPage = SettingsPage("Layouts & widgets") {
        val book = LayoutStore.book
        section("Saved layouts", footer = "Tip: on the Dashboard page, long-press to edit directly; swipe up/down to switch layouts.") {
            book.layouts.forEach { l ->
                nav((if (l.id == book.activeId) "✓  " else "") + l.name, "${l.tiles.size} tiles" + if (l.locked) " · 🔒" else "") { layoutEditor(l.id) }
            }
            action("New layout") {
                val id = LayoutStore.newId()
                LayoutStore.book = book.copy(layouts = book.layouts + Layout(id, "Layout ${book.layouts.size + 1}", 4, false,
                    listOf(Tile(id * 100, TileSize.LARGE, listOf(WidgetType.CLOCK)))))
            }
        }
        section(footer = "Automatically switch layouts for mornings, work hours, evenings, music and night (set per layout).") {
            toggle("Context switching", book.autoContext) { LayoutStore.book = LayoutStore.book.copy(autoContext = it) }
            action("Restore default layouts") { LayoutStore.book = LayoutBook.defaults() }
        }
    }

    fun layoutEditor(id: Long): SettingsPage = SettingsPage("Layout") {
        val book = LayoutStore.book
        val l = book.layouts.firstOrNull { it.id == id } ?: return@SettingsPage
        fun save(n: Layout) = LayoutStore.save(n)
        section {
            textField("Name", l.name) { if (it.isNotBlank()) save(l.copy(name = it.take(24))) }
            choice("Columns", listOf(2, 3, 4), l.columns, { "$it columns" }) { save(l.copy(columns = it)) }
            choice("Use automatically", LayoutContext.entries.toList(), l.context, { it.label }) { save(l.copy(context = it)) }
            toggle("Lock layout", l.locked) { save(l.copy(locked = it)) }
            if (l.id != book.activeId) action("Show this layout") { LayoutStore.book = book.copy(activeId = l.id) }
        }
        section("Tiles", footer = "Order here is the order on screen. Tiles are placed automatically without overlaps.") {
            l.tiles.forEachIndexed { i, t ->
                nav("${i + 1}. " + t.stack.joinToString(" / ") { it.label }, t.size.label) { tileEditor(l.id, t.id) }
            }
            if (!l.locked) choice("Add widget", WidgetType.entries.toList(), WidgetType.CLOCK, { it.label }) { w ->
                save(l.copy(tiles = l.tiles + Tile(LayoutStore.newId(), TileSize.SMALL, listOf(w))))
            }
        }
        section {
            action("Duplicate layout") { LayoutStore.book = book.duplicate(l.id, LayoutStore.newId()) }
            if (book.layouts.size > 1) action("Delete layout", Look.c(ColorRole.WARNING)) {
                screen.confirm("Delete “${l.name}”?", "This can't be undone.", "Delete") { LayoutStore.book = LayoutStore.book.remove(l.id); screen.pop() }
            }
        }
    }

    fun tileEditor(layoutId: Long, tileId: Long): SettingsPage = SettingsPage("Tile") {
        val l = LayoutStore.book.layouts.firstOrNull { it.id == layoutId } ?: return@SettingsPage
        val t = l.tiles.firstOrNull { it.id == tileId } ?: return@SettingsPage
        fun save(n: Tile) = LayoutStore.save(l.copy(tiles = l.tiles.map { if (it.id == t.id) n else it }))
        section {
            choice("Size", TileSize.entries.toList(), t.size, { "${it.label} (${if (it == TileSize.FULL) "all" else "${it.cols}×${it.rows}"})" }) { save(t.copy(size = it)) }
        }
        section("Widget stack", footer = "Add more than one widget to make a stack: swipe up/down on the tile to cycle, or rotate on a timer.") {
            t.stack.forEachIndexed { i, w ->
                if (t.stack.size > 1) action("−  ${w.label}", Look.c(ColorRole.TEXT)) { save(t.copy(stack = t.stack.filterIndexed { k, _ -> k != i })) }
                else info("Widget", w.label)
            }
            choice("Add to stack", WidgetType.entries.toList(), WidgetType.CLOCK, { it.label }) { save(t.copy(stack = t.stack + it)) }
            if (t.stack.size > 1) choice("Rotate", listOf(0, 5, 10, 20, 60), t.rotateSec, { if (it == 0) "Off" else "Every $it s" }) { save(t.copy(rotateSec = it)) }
        }
        section {
            val idx = l.tiles.indexOf(t)
            if (idx > 0) action("Move earlier") { LayoutStore.save(l.copy(tiles = l.tiles.toMutableList().apply { removeAt(idx); add(idx - 1, t) })) }
            if (idx < l.tiles.size - 1) action("Move later") { LayoutStore.save(l.copy(tiles = l.tiles.toMutableList().apply { removeAt(idx); add(idx + 1, t) })) }
            action("Remove tile", Look.c(ColorRole.WARNING)) { LayoutStore.save(l.copy(tiles = l.tiles.filter { it.id != t.id })); screen.pop() }
        }
    }

    // ---- Photos -------------------------------------------------------------------------
    fun photos(host: SettingsHost) = SettingsPage("Photo frame") {
        section(footer = "Photos stay on this phone. NØVA can only read the one folder you choose.") {
            action(if (Prefs.photoTreeUri.isBlank()) "Choose photo folder…" else "Change photo folder…") { host.pickPhotoFolder() }
            if (Prefs.photoTreeUri.isNotBlank()) info("Folder", android.net.Uri.decode(Prefs.photoTreeUri.substringAfterLast("/")).substringAfterLast(":").ifBlank { "Selected" })
        }
        section("Slideshow") {
            choice("Change every", listOf(5, 10, 15, 30, 60, 300), Prefs.photoIntervalSec, { if (it < 60) "$it s" else "${it / 60} min" }) { Prefs.photoIntervalSec = it }
            toggle("Shuffle", Prefs.photoShuffle) { Prefs.photoShuffle = it }
        }
        section("Clock overlay") {
            toggle("Show clock", Prefs.photoClockOverlay) { Prefs.photoClockOverlay = it }
            choice("Position", listOf(0, 1, 2, 3), Prefs.photoOverlayCorner, { listOf("Top left", "Top right", "Bottom left", "Bottom right")[it] }) { Prefs.photoOverlayCorner = it }
            slider("Opacity", 20, 100, Prefs.photoOverlayOpacity, { "$it%" }) { Prefs.photoOverlayOpacity = it }
        }
    }

    // ---- Weather ------------------------------------------------------------------------
    fun weather() = SettingsPage("Weather") {
        section(footer = "Weather data by Open-Meteo.com (free, no account). Only the place you pick is sent — no GPS.") {
            info("Location", Prefs.weatherPlace.ifBlank { "Not set" })
            textField("Search place", "", hint = "e.g. Prayagraj") { q ->
                if (q.isBlank()) return@textField
                Weather.search(q) { r ->
                    r.onSuccess { places ->
                        if (places.isEmpty()) screen.confirm("No results", "No place found for “$q”.", "OK") {}
                        else screen.push(SettingsPage("Results") {
                            section {
                                places.forEach { pl ->
                                    action("${pl.name}, ${pl.detail}") {
                                        Prefs.weatherPlace = pl.name; Prefs.weatherLat = pl.lat.toFloat(); Prefs.weatherLon = pl.lon.toFloat()
                                        Weather.refresh(force = true); screen.pop()
                                    }
                                }
                            }
                        })
                    }.onFailure { screen.confirm("Search failed", "Check the internet connection.", "OK") {} }
                }
            }
            choice("Units", TempUnit.entries.toList(), Prefs.tempUnit, { it.label }) { Prefs.tempUnit = it }
            action("Refresh now") { Weather.refresh(force = true) }
            if (DataHub.weatherStatus.isNotBlank()) info("Status", DataHub.weatherStatus)
        }
    }

    // ---- Voice / AI / wake word ---------------------------------------------------------
    fun voice(host: SettingsHost) = SettingsPage("Voice & speech") {
        section(footer = "Touch-to-talk uses Android speech recognition (offline when the phone has offline speech installed).") {
            val granted = host.hasPermission(Manifest.permission.RECORD_AUDIO)
            info("Microphone", if (granted) "Allowed" else "Not allowed")
            if (!granted) action("Allow microphone") { host.requestPermission(Manifest.permission.RECORD_AUDIO) }
            choice("Language", listOf("en-IN", "en-US", "en-GB", "hi-IN"), Prefs.voiceLanguage,
                { mapOf("en-IN" to "English (India)", "en-US" to "English (US)", "en-GB" to "English (UK)", "hi-IN" to "Hindi")[it] ?: it }) { Prefs.voiceLanguage = it }
        }
        section("Spoken answers") {
            toggle("Speak replies", Prefs.speakReplies) { Prefs.speakReplies = it }
            info("Text-to-speech", host.speakerStatus())
        }
        section("Try saying", footer = null) {
            note("“Show dashboard” · “Go to pet mode” · “Let's talk” · “Go to sleep”\n“Set a timer for 5 minutes” · “Remind me to drink water in 30 minutes”\n" +
                "“Pause” · “Next song” · “Play lofi on Spotify” · “Move the music to Fire TV”\n“What's the weather” · “Night mode on” · “Turn on desk lamp”")
        }
    }

    fun ai() = SettingsPage("Cloud AI") {
        section(footer = "General questions go to your own Cloudflare Worker (see cloudflare-worker/README.md). The AI key stays on Cloudflare; " +
            "this phone only stores the Worker address and your access token. Every action the AI proposes is checked against NØVA's allowlist.") {
            toggle("Use Cloud AI", Prefs.aiEnabled) { Prefs.aiEnabled = it }
            textField("Worker URL", Prefs.aiEndpoint, hint = "https://andorid-ai-standby.<you>.workers.dev") { Prefs.aiEndpoint = AiClient.baseUrl(it) }
            textField("Access token", Prefs.aiToken, secret = true) { Prefs.aiToken = it }
            action("Test connection") { AiClient.health { msg -> screen.confirm("Cloud AI", msg, "OK") {} } }
        }
    }

    fun wake(host: SettingsHost) = SettingsPage("Wake word") {
        val ready = WakeWord.modelReady(screen.context)
        section(footer = "“Hey NØVA” is detected entirely on this phone (Vosk, ~40 MB model). While on, a notification shows the microphone is in use. " +
            "Touch-to-talk always works. Measure battery and false triggers before leaving it on.") {
            info("Voice model", if (ready) "Installed" else "Not downloaded")
            if (!ready) action("Download model (Wi-Fi recommended)") {
                WakeWord.downloadModel(screen.context, { host.toast(it) }) { r ->
                    r.onSuccess { host.toast("Model installed"); Prefs.notifyChanged() }.onFailure { host.toast("Download failed: ${it.message}") }
                }
            }
            if (ready) toggle("Listen for “Hey NØVA”", Prefs.wakeWordEnabled) { Prefs.wakeWordEnabled = it; host.wakeWordChanged() }
            choice("Sensitivity", listOf(1, 2, 3), Prefs.wakeSensitivity, { listOf("", "Strict (fewer false triggers)", "Balanced", "Loose (catches more)")[it] }) { Prefs.wakeSensitivity = it }
        }
        section("Benchmark") {
            info("Status", WakeWord.status)
            info("Since start", WakeWord.benchmark())
        }
    }

    // ---- Music --------------------------------------------------------------------------
    fun music(host: SettingsHost) = SettingsPage("Music & Spotify") {
        section("Control music on this phone", footer = "Notification access lets NØVA show and control Spotify (or any player): play/pause, skip, artwork. " +
            "Audio follows Android's output — connect a Bluetooth speaker and the sound goes there.") {
            info("Notification access", if (NovaNotificationService.enabled(screen.context)) "On" else "Off")
            action("Open notification access settings") { host.openNotificationAccess() }
            info("Audio output", DataHub.audioRoute)
            action("Bluetooth settings") { host.openSystemScreen(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }
            if (!host.hasPermission("android.permission.BLUETOOTH_CONNECT") && android.os.Build.VERSION.SDK_INT >= 31)
                action("Allow Bluetooth status") { host.requestPermission("android.permission.BLUETOOTH_CONNECT") }
            toggle("NØVA dances while music plays", Prefs.danceWithMusic) { Prefs.danceWithMusic = it }
        }
        section("Spotify Connect (Fire TV, speakers)", footer = "Needs a free Spotify developer app: developer.spotify.com → Create app → Redirect URI " +
            "${Spotify.REDIRECT} → Android package com.nova.zenfone. Remote playback control requires Spotify Premium.") {
            textField("Client ID", Prefs.spotifyClientId, hint = "32-character Client ID") { Prefs.spotifyClientId = it }
            if (Spotify.configured) {
                if (Spotify.signedIn) {
                    info("Account", "Signed in")
                    action("Sign out", Look.c(ColorRole.WARNING)) { Spotify.signOut() }
                } else action("Sign in to Spotify") { host.spotifySignIn() }
            }
        }
    }

    fun access(host: SettingsHost) = SettingsPage("Notifications & calendar") {
        section("Calendar", footer = "Shows your next events. NØVA only reads, never changes your calendar.") {
            val granted = CalendarSource.granted(screen.context)
            info("Calendar access", if (granted) "Allowed" else "Not allowed")
            if (!granted) action("Allow calendar") { host.requestPermission(Manifest.permission.READ_CALENDAR) }
        }
        section("Notifications", footer = "Leave the app list empty to show all apps, or type package names separated by commas (e.g. com.whatsapp).") {
            info("Notification access", if (NovaNotificationService.enabled(screen.context)) "On" else "Off")
            action("Open notification access settings") { host.openNotificationAccess() }
            textField("Only these apps", Prefs.raw.getString("notifApps", "") ?: "", hint = "com.whatsapp, com.google.android.gm") {
                Prefs.raw.edit().putString("notifApps", it.replace(" ", "")).apply(); Prefs.notifyChanged()
            }
        }
        section("Alarms", footer = "Timers and reminders use exact alarms where Android allows them.") {
            if (android.os.Build.VERSION.SDK_INT >= 31) action("Allow exact alarms") { host.openSystemScreen("android.settings.REQUEST_SCHEDULE_EXACT_ALARM") }
            if (android.os.Build.VERSION.SDK_INT >= 33 && !host.hasPermission("android.permission.POST_NOTIFICATIONS"))
                action("Allow notifications") { host.requestPermission("android.permission.POST_NOTIFICATIONS") }
        }
    }

    // ---- Smart home ---------------------------------------------------------------------
    fun smartHome(): SettingsPage = SettingsPage("Smart home") {
        val list = SmartHome.devices()
        section("Devices", footer = "Each device is an ESP32 / Raspberry Pi endpoint you run (examples in extras/). NØVA sends POST {\"command\":\"on|off|toggle\"} " +
            "with your token. Say “turn on <name>” — NØVA always asks before switching devices.") {
            list.forEachIndexed { i, d -> nav(d.name, d.url.substringAfter("://").substringBefore('/')) { deviceEditor(i) } }
            textField("Add device", "", hint = "Device name, e.g. desk lamp") { name ->
                if (name.isNotBlank()) SmartHome.save(list + SmartDevice(name.lowercase(), "", ""))
            }
        }
    }

    fun deviceEditor(index: Int): SettingsPage = SettingsPage("Device") {
        val list = SmartHome.devices()
        val d = list.getOrNull(index) ?: return@SettingsPage
        fun save(n: SmartDevice) = SmartHome.save(list.toMutableList().also { it[index] = n })
        section(footer = SmartHome.checkUrl(d.url)?.let { "⚠ $it" } ?: "Address looks good.") {
            textField("Name", d.name) { if (it.isNotBlank()) save(d.copy(name = it.lowercase())) }
            textField("Address", d.url, hint = "https://192.168.1.50/command") { save(d.copy(url = it)) }
            textField("Access token", d.token, secret = true) { save(d.copy(token = it)) }
        }
        section {
            action("Send “toggle” now") { SmartHome.command(d, "toggle") { r -> screen.confirm(d.name, r.fold({ it }, { it.message ?: "Failed" }), "OK") {} } }
            action("Remove device", Look.c(ColorRole.WARNING)) { SmartHome.save(list.filterIndexed { i, _ -> i != index }); screen.pop() }
        }
    }

    // ---- Diagnostics --------------------------------------------------------------------
    fun diagnostics(host: SettingsHost) = SettingsPage("Diagnostics") {
        val b = DataHub.battery
        section("Device") {
            info("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            info("Model", android.os.Build.MODEL)
            info("Battery", if (b.percent >= 0) "${b.percent}% · ${if (b.charging) "charging" else "on battery"}" else "—")
            info("Battery temperature", if (b.tempC.isNaN()) "—" else String.format("%.1f °C", b.tempC))
            info("Light sensor", if (host.hasLightSensor()) Prefs.lightSensorStatus else "None")
        }
        section("Permissions") {
            info("Microphone", if (host.hasPermission(Manifest.permission.RECORD_AUDIO)) "Allowed" else "Not allowed")
            info("Calendar", if (host.hasPermission(Manifest.permission.READ_CALENDAR)) "Allowed" else "Not allowed")
            info("Notification access", if (NovaNotificationService.enabled(screen.context)) "On" else "Off")
        }
        section("Last crash", footer = "Crashes are saved only on this phone so you can report them.") {
            note(Prefs.lastCrash.ifBlank { "No crashes recorded 🎉" })
            if (Prefs.lastCrash.isNotBlank()) action("Clear") { Prefs.lastCrash = "" }
        }
    }
}
