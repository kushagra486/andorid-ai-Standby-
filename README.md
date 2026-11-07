# NØVA — ZenFone Edition

An autonomous AI desk companion for the **ASUS ZenFone 3 Max ZC520TL** (Android 7.0 / API 24).
A minimal black faceplate with two glowing cyan eyes and a short cyan mouth that lives on your desk.

> **Status: v0.2.0 — Stage 2 (personality engine + StandBy-style UI).** Voice, AI, Spotify, Bluetooth and
> Fire TV are planned for later stages and are **not** implemented yet.

## What works

| Feature | Details |
|---|---|
| Pixel Pet face | Canvas-drawn faceplate, cyan oval eyes with soft glow, pill mouth |
| Motion | Spring physics (gentle overshoot), blinking, eye wandering, breathing, reaction hop |
| 14 expressions | Idle, Happy, Mischievous, Curious, Surprised, Confused, Annoyed, Thinking, Listening, Dancing, Error, Sleepy, Sleeping (+ night variants) |
| Personality engine | Priority-based state machine (`PetBrain`), silent idle micro-behaviours, error status line |
| Touch | Tap → reaction + haptic + sound; 7 rapid taps → Annoyed; tap wakes it from sleep |
| Sleep | 5 min idle → Sleepy, 15 min → Sleeping (1 / 5 min at night) |
| Sounds | 10 original synthesised sounds, volume setting, limiter (≥200 ms apart, ≤10 per minute) |
| Clock page | StandBy-inspired widget cards: big clock + date, battery ring, Settings card; swipe or long-press |
| Night mode | Off / 22:00–07:00 / Always; **Red** (StandBy-style) or **Dim** look; mutes sounds |
| Settings | iOS-style grouped list: sounds, volume, animation intensity, night mode/look, test all expressions |
| Battery care | Adaptive frame rate: 30 fps animating, 25 idle, 15 sleepy/calm, 8 sleeping; paused in background |

## Navigation

- **Face** → swipe sideways or long-press → **Clock**
- **Clock** → tap the *Settings* card → **Settings**; tap/swipe elsewhere → back to the face
- Clock returns to the face after 30 s, Settings after 60 s of inactivity
- **Settings → Test all expressions** plays every expression with its sound (tap to stop)

## Tests

`./gradlew testDebugUnitTest` runs the personality/sound/settings unit tests and renders every screen at
1280×720 into `app/build/screenshots/` (Robolectric, no emulator). CI uploads those PNGs with each build.

## Download the APK

**Every version is on the [Releases page](../../releases)** as `NOVA-v<version>.apk`.

How releases are made (`.github/workflows/build-apk.yml`):

- Every push builds an APK, downloadable from that run under **Actions → Artifacts**.
- Every push to `main` publishes a Release `v<versionName>` with the APK, once per version.
- To ship a new version, bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
- Pushing a tag such as `v0.2.0`, or **Actions → Build APK → Run workflow**, also publishes it.

## Install on the ZenFone

**Option A — no PC:** copy the `.apk` to the phone (Google Drive, USB, Telegram-to-self…), open it in
File Manager and allow "Install from unknown sources" when Android asks.

**Option B — USB/ADB:**
```
adb devices
adb install -r NOVA-debug.apk
```
(Enable *Developer options* by tapping **Settings → About → Software info → Build number** 7 times,
then turn on **USB debugging**.)

> All builds are signed with the same committed key (`app/nova-debug.keystore`), so each new version installs
> over the previous one and keeps your settings. If you installed the very first APK sent in chat, uninstall
> it once before installing from Releases. A private release key comes in Stage 10.

## Build it yourself

**Android Studio:** File → Open → this folder → wait for Gradle sync → Build → Build APK(s).

**Command line (no IDE):** JDK 17+ and the Android SDK (platform 35) are required.
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/com/nova/
├── MainActivity.kt              screens (face / clock / settings), cross-fades, lifecycle
├── pet/PetBrain.kt             personality state machine (pure Kotlin, unit-tested)
├── pet/PetView.kt              face renderer: springs, motions, night look, adaptive fps
├── pet/Expression.kt           expression geometry, priority and motion table
├── audio/SoundFx.kt            synthesised sounds + SoundLimiter
├── dashboard/DashboardView.kt  StandBy-style clock page
├── settings/                   NovaSettings (prefs) + iOS-style SettingsView
└── ui/Theme.kt                 colours, fonts, night tint
```

## Roadmap (from the project report)

Stage 2 personality polish · Stage 3 full dashboard (weather, Wi-Fi/BT, calendar) · Stage 4 touch-to-talk + TTS ·
Stage 5 Cloudflare Worker AI backend · Stage 6 wake word · Stage 7 Spotify + Bluetooth · Stage 8 Fire TV ·
Stage 9 smart-home · Stage 10 signed release.
