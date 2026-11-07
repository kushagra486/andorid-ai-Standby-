# NØVA roadmap — Master Specification v1.1

Source of truth: *NØVA Master Project Specification v1.1* (owner decisions of 8 Nov 2026:
Hybrid theme · Pixel Pet default · Hybrid gestures · Local photos · Automatic night mode ·
direct + Settings widget editing).

Status legend: ✅ built & unit/screenshot-tested · 📱 needs on-device verification (ZC520TL) ·
⏳ planned · 🔑 needs something from the owner (account/key/hardware)

| Stage | Workstream | Status | Release |
|---|---|---|---|
| 1 | Android foundation & Pixel Pet — immersive landscape shell, face, blink/tap/beep, lifecycle | ✅ 📱 | v0.1.0 |
| 2 | Pet personality & sound — state machine, 14 expressions, original sounds, throttling, mute, settings | ✅ 📱 | v0.2.0 |
| 3 | StandBy navigation framework — hybrid gesture router, layout families, vertical variations, mode persistence, quick menu; v1.1 colour tokens | ⏳ | v0.3.0 |
| 4 | Clock gallery & widget layouts — digital/analog/minimal/flip-inspired/NØVA clocks, dual widget, modular grid | ⏳ | v0.4.0 |
| 5 | Widget editor & saved layouts — drag/resize/reorder, Settings editor, save/duplicate/lock, stacks, schema versioning | ⏳ | v0.5.0 |
| 6 | Local photo frame & themes — folder picker, slideshow, overlays, NØVA Cyan/Midnight/Warm Night/Frost/Photo Ambient/custom | ⏳ | v0.6.0 |
| 7 | Automatic night mode — schedule, light-sensor capability test, override, dim cyan/red-amber/near-black, charging-aware StandBy | ⏳ (partial: schedule + red/dim done in v0.2.0) | v0.7.0 |
| 8 | Dashboard integrations — weather (Open-Meteo), calendar, battery, Wi-Fi, Bluetooth, reminders, timer | ⏳ | v0.8.0 |
| 9 | Touch-to-talk & speech — mic permission, SpeechRecognizer, transcript, cancel, TTS, Assistant View | ⏳ | v0.9.0 |
| 10 | AI backend — Cloudflare Worker, auth, rate limits, strict schema, Kotlin action allowlist | ⏳ 🔑 Cloudflare account + Gemini key | v0.10.0 |
| 11 | Local wake word — sherpa-onnx KWS benchmark, foreground service, mic indicator | ⏳ 📱 | v0.11.0 |
| 12 | Spotify & Bluetooth — App Remote, Music View, audio routing, paired devices | ⏳ 🔑 Spotify developer app (client ID) | v0.12.0 |
| 13 | Fire TV playback — Spotify Connect transfer or documented limitation | ⏳ 🔑 Spotify Premium + Fire TV | v0.13.0 |
| 14 | Smart-home (optional) — Pi/ESP32 over authenticated MQTT/HTTPS | ⏳ 🔑 hardware | v0.14.0 |
| 15 | Hardening & release — endurance, battery/heat, accessibility, private release key, handover docs | ⏳ 📱 | v1.0.0 |

Every stage ships as a GitHub Release with its APK (`NOVA-v<version>.apk`).

## Acceptance gates that only the physical phone can close

A (installs on ZC520TL), B (pet visual/touch), 30-minute idle/no-crash, battery & heat,
light-sensor availability, gestures at real resolution, wake-word accuracy, Spotify/Bluetooth/Fire TV.
Unit tests and Robolectric screenshots cover logic and layout; they do not replace these.
