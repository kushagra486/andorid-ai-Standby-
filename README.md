# NØVA — ZenFone Edition

An autonomous AI desk companion for the **ASUS ZenFone 3 Max ZC520TL** (Android 7.0 / API 24).
A minimal black faceplate with two glowing cyan eyes and a short cyan mouth that lives on your desk.

> **Status: v0.1.0 — Stage 1 (Pixel Pet foundation).** Voice, AI, Spotify, Bluetooth and Fire TV
> are planned for later stages and are **not** implemented yet.

## What works in v0.1.0

| Feature | Details |
|---|---|
| Pixel Pet face | Canvas-drawn faceplate, cyan oval eyes with soft glow, pill mouth |
| Animation | Blinking (incl. double-blink), eye wandering, breathing, reaction hop, ~30 fps cap |
| Expressions | Idle, Happy, Mischievous, Curious, Surprised, Confused, Annoyed, Sleepy, Sleeping |
| Touch | Tap → random playful reaction + haptic + chirp; 7 rapid taps → Annoyed |
| Sleep | 5 min idle → Sleepy, 15 min → Sleeping (floating z's); tap to wake (Surprised) |
| Sounds | Original electronic chirps synthesised in code (no audio files) |
| Dashboard | Long-press → big clock, date, seconds, battery %, charging, sound on/off |
| Device | Landscape, immersive full-screen, screen kept on, animation paused in background |

## Get the APK without Android Studio

Every push to GitHub builds an APK automatically (`.github/workflows/build-apk.yml`):

1. Open the repo on GitHub → **Actions** → latest **Build APK** run.
2. Download the **NOVA-debug-apk** artifact (a zip containing the `.apk`).
3. Pushing a tag such as `v0.1.0` also attaches the APK to a **GitHub Release** for a permanent link.

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

> Debug APKs built on different machines are signed with different debug keys. If an update refuses to
> install over the old one, uninstall NØVA first. A permanent release key comes in Stage 10.

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
├── MainActivity.kt          mode switching, immersive/landscape, lifecycle
├── pet/PetView.kt           face renderer, animation loop, gestures, idle/sleep logic
├── pet/Expression.kt        expression geometry table
├── audio/SoundFx.kt         synthesised chirps
└── dashboard/DashboardView.kt  clock / date / battery / sound toggle
```

## Roadmap (from the project report)

Stage 2 personality polish · Stage 3 full dashboard (weather, Wi-Fi/BT, calendar) · Stage 4 touch-to-talk + TTS ·
Stage 5 Cloudflare Worker AI backend · Stage 6 wake word · Stage 7 Spotify + Bluetooth · Stage 8 Fire TV ·
Stage 9 smart-home · Stage 10 signed release.
