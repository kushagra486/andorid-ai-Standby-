# NØVA — ZenFone Edition

An autonomous AI desk companion and StandBy-style smart display for the **ASUS ZenFone 3 Max ZC520TL**
(Android 7.0 / API 24, works on newer Android too). A minimal faceplate with two glowing eyes lives on
your desk; swipe for clocks, widgets, photos, music and a voice assistant.

**Download:** [Releases](../../releases) → `NOVA-v1.0.0.apk` · **Spec:** Master Project Specification v1.1 ·
**Status of every requirement:** [docs/requirements.md](docs/requirements.md)

## Features

| Area | What you get |
|---|---|
| **Pixel Pet** | Spring-animated face, 14 expressions, personality engine, tap/long-press, sleeps when idle, dances to music, original synthesised sounds with a no-noise limiter |
| **StandBy pages** | Swipe ←/→: Pixel Pet · Clocks · Dual widget · Dashboard · Photo frame · Music · Assistant. Swipe ↑/↓ for variations. Swipe down from the top edge for quick controls. Long-press the pet for the quick menu. |
| **Clocks** | Digital, analog, minimal, flip, NØVA (eyes follow the seconds), date & seconds; 12/24 h |
| **Widgets** | Clock, date, weather, next event, battery, Wi-Fi, Bluetooth, music, reminders, timer, notifications, pet status — small/medium/large/full, 2–4 columns, stacks with rotation |
| **Layout editing** | Long-press the dashboard: jiggle, drag to reorder, resize, remove, add, columns, Done. Or Settings → Layouts (rename, duplicate, lock, context switching for morning/work/evening/music/night). |
| **Themes & colours** | NØVA Cyan (spec default), Midnight, Warm Night, Frost, Photo Ambient, and **Custom**: pick your own colour for each of 17 parts (background, tiles, borders, faceplate, eyes, mouth, glow, clock digits, analog hands, accents, text, switches, battery ring, music, alerts) with a colour picker, hex codes and live preview |
| **Night mode** | Automatic (schedule + optional validated light sensor), manual override, dim cyan / red-amber / near-black palettes, screen dimming, muted sounds |
| **Photo frame** | Your chosen local folder only, slideshow, fill/fit/clock-focus, clock overlay position & opacity |
| **Assistant** | Touch-to-talk (or type), offline commands (timers, reminders, music, pages, night mode, devices, weather/time), spoken answers, optional Cloud AI via your Cloudflare Worker, optional on-device "Hey NØVA" wake word |
| **Music** | Controls Spotify or any player (notification access), album art, progress, Bluetooth routing, Spotify Connect → Fire TV / speakers (Spotify developer app + Premium) |
| **Smart home** | "Turn on desk lamp" → your ESP32 / Raspberry Pi over HTTPS (LAN HTTP) with a token; always asks first |
| **Care for old hardware** | Canvas-only rendering, adaptive frame rate, everything paused in background, heat safeguard, keep-awake options, charging-aware StandBy |

## Install

Copy the APK to the phone and open it (allow *Unknown sources*), or `adb install -r NOVA-v1.0.0.apk`.
Then open **Settings** (long-press the pet → ⚙) and set your weather place, photo folder and permissions.

## Project layout

```
app/                 Android app (Kotlin) — see docs/architecture.md
cloudflare-worker/   Cloud AI proxy (GET /v1/health, POST /v1/chat) + tests
extras/              ESP32 sketch and Raspberry Pi script for smart-home devices
scripts/             make-release-key.sh (your private signing key)
docs/                architecture · requirements · testing · permissions · troubleshooting · handover · roadmap
```

## Build & test

```
./gradlew testDebugUnitTest assembleDebug     # 48 unit tests + 37 rendered screenshots → app/build/screenshots
cd cloudflare-worker && npm test              # 10 Worker tests
```

GitHub Actions runs all of this on every push and publishes a Release for each new `versionName`
(see [docs/handover.md](docs/handover.md)).
