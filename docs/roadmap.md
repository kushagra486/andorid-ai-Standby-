# NØVA roadmap — Master Specification v1.1

Source of truth: *NØVA Master Project Specification v1.1* (owner decisions of 8 Nov 2026:
Hybrid theme · Pixel Pet default · Hybrid gestures · Local photos · Automatic night mode ·
direct + Settings widget editing).

Status legend: ✅ built & unit/screenshot-tested · 📱 needs on-device verification (ZC520TL) ·
⏳ planned · 🔑 needs something from the owner (account/key/hardware)

| Stage | Workstream | Status | Release |
|---|---|---|---|
| 1 | Android foundation & Pixel Pet | ✅ 📱 | v0.1.0 |
| 2 | Pet personality & sound | ✅ 📱 | v0.2.0 |
| 3 | StandBy navigation framework (gesture router, families, variations, persistence, quick menu, quick controls) | ✅ 📱 | v1.0.0 |
| 4 | Clock gallery & widget layouts (6 clock faces, dual widget, modular grid) | ✅ | v1.0.0 |
| 5 | Widget editor & saved layouts (direct + Settings editing, stacks, lock, duplicate, context switching, schema) | ✅ | v1.0.0 |
| 6 | Local photo frame & themes (+ custom colour editor for every UI part) | ✅ 📱 | v1.0.0 |
| 7 | Automatic night mode (schedule, sensor validation, palettes, charging, keep-awake, heat safeguard) | ✅ 📱 | v1.0.0 |
| 8 | Dashboard integrations (weather, calendar, battery, Wi-Fi, Bluetooth, reminders, timer, notifications) | ✅ 📱 | v1.0.0 |
| 9 | Touch-to-talk & speech, Assistant view, TTS | ✅ 📱 | v1.0.0 |
| 10 | AI backend (Cloudflare Worker, auth, rate limit, schema, allowlist) | ✅ code + tests · 🔑 deploy needs your Cloudflare account | v1.0.0 |
| 11 | Local wake word (Vosk, foreground service, benchmark) | ✅ 📱 (measure battery/false triggers) | v1.0.0 |
| 12 | Spotify & Bluetooth (MediaSession control, Music view, audio route) | ✅ 📱 | v1.0.0 |
| 13 | Fire TV playback (Spotify Connect transfer + manual fallback) | ✅ code · 🔑 Spotify Client ID + Premium · 📱 | v1.0.0 |
| 14 | Smart home (HTTPS adapter, ESP32/Pi examples) | ✅ (MQTT not included) | v1.0.0 |
| 15 | Hardening & release (crash log, docs, signing workflow) | ✅ · 🔑 your private release key · 📱 endurance | v1.0.0 |

Every stage ships as a GitHub Release with its APK (`NOVA-v<version>.apk`).

## Acceptance gates that only the physical phone can close

A (installs on ZC520TL), B (pet visual/touch), 30-minute idle/no-crash, battery & heat,
light-sensor availability, gestures at real resolution, wake-word accuracy, Spotify/Bluetooth/Fire TV.
Unit tests and Robolectric screenshots cover logic and layout; they do not replace these.
