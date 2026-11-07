# Requirements traceability (Master Specification v1.1)

✅ implemented + automated test/screenshot · 📱 needs verification on the physical ZC520TL ·
🔑 needs an owner account/key · ⚠ implemented with a documented limitation

| Spec | Requirement | Implementation | Status |
|---|---|---|---|
| §3.1 | Master face: faceplate, cyan oval eyes, pill mouth, restrained glow | `pet/PetView.kt` | ✅📱 |
| §3.3 | 13 expression states incl. Listening, Thinking, Dancing, Error | `pet/Expression.kt`, `PetBrain.kt` | ✅ (`PetBrainTest`, screenshots) |
| §3.4 | Tap reaction, long-press quick menu, swipe navigation, idle return | `PetView`, `StandbyHost`, `MainActivity.checkIdle` | ✅📱 |
| §3.4 | Voice: "show dashboard", "go to pet mode", "let's talk", "go to sleep" | `actions/IntentRouter` | ✅ (`ActionsTest`) |
| §4.1 | 1280×720 responsive, pause in background, haptics, local sounds, intensity/sound/night settings | `PetView`, `SoundFx`, Settings | ✅📱 |
| §4.2 | Time/date 12/24 h, battery, Wi-Fi, Bluetooth, weather (Open-Meteo), calendar after permission, Spotify widget | `widgets/Widgets.kt`, `integrations/*` | ✅📱 |
| §4.2 | Enable/disable/reorder widgets, dim night mode, screen-awake setting | `TileGridView`, `SettingsPages` | ✅ |
| §4.3 | Touch-to-talk, listening state, transcript, cancel, SpeechRecognizer (prefer offline), TTS | `voice/*` | ✅📱 |
| §4.3 | Wake word evaluated after benchmarking | `voice/WakeWord.kt` (Vosk, off by default, benchmark readout) | ⚠📱 |
| §4.4 | Worker proxy, key server-side, auth, rate limit, strict schema, allowlist, no URLs/code, confirmation | `cloudflare-worker/`, `actions/`, `ai/AiClient.kt` | ✅ (`ActionsTest`, worker tests) 🔑 |
| §4.5 | Spotify on phone, Bluetooth routing, Fire TV via Spotify Connect, destination display, manual fallback | `integrations/NotificationHub.kt` (MediaHub), `music/*` | ✅📱🔑 (Premium for Connect) |
| §4.6 | Bluetooth state & paired list, volume/brightness, alarms/timers (exact-alarm rules), calendar/notifications after consent | `SystemStatus`, `QuickControls`, `Alarms` | ✅📱 |
| §4.6 | Pi/ESP32 via authenticated HTTPS | `home/SmartHome.kt`, `extras/` | ⚠ MQTT not included (HTTPS only) |
| §14.1 | Hybrid theme, Pixel Pet default, hybrid gestures, local photos, automatic night, direct + Settings editing | throughout | ✅ |
| §14.2 | #05090D / #36DFFF / #147CFF tokens; NØVA Cyan, Midnight, Warm Night, Frost, Photo Ambient, custom accents | `ui/Palette.kt`, `settings/AppearancePages.kt` | ✅ (`RobolectricUnitTests`) — custom theme covers **every** colour role |
| §14.3 | Families: Pixel Pet, Clock Gallery, Dual Widget, Modular Dashboard, Photo Frame, Music View, Assistant View | `core/Pages.kt` | ✅ (screenshots) |
| §14.4 | Swipe L/R families, U/D variations, tap widget, long-press edit, drag/resize, top-edge quick controls, precedence, button alternatives | `StandbyHost`, `TileGridView`, `QuickMenu` | ✅📱 · pinch & two-finger gestures not implemented (quick controls instead) |
| §14.5 | S/M/L/full spans, 12-widget catalogue, 2/3/4 columns, add/remove/reorder/resize/save/duplicate/rename/lock, stacks + rotation, multiple layouts + context switching, versioned schema | `widgets/Layouts.kt`, `SettingsPages.layouts` | ✅ (`LayoutsTest`) |
| §14.6 | Local folder via document picker, slideshow, overlay position/opacity, pause/next/prev, rotation, large images, low memory, missing files | `photos/PhotoFrameView.kt` | ✅📱 |
| §14.7 | Schedule + validated light sensor, fallback, override, dim cyan / red-amber / near-black, reduced sound/animation, keep-awake, charging-aware, LCD note | `core/NightController.kt`, `MainActivity.applyWindow` | ✅ (`NightRulesTest`) 📱 |
| §8.1 A–I | Acceptance gates | see `docs/testing.md` | 📱 |
