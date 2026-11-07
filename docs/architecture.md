# Architecture

```
┌────────────────────────── Android app (Kotlin, Views/Canvas, minSdk 24) ──────────────────────────┐
│ MainActivity ── StandbyHost (gesture router) ── FamilyPage × 7                                     │
│   │               PET · CLOCK · DUAL · DASHBOARD · PHOTOS · MUSIC · ASSISTANT                       │
│   ├─ QuickMenu (long-press pet) · QuickControls (top-edge pull) · SettingsScreen (iOS-style stack) │
│   ├─ Providers (foreground only): SystemStatus · Weather · CalendarSource · NightController        │
│   ├─ Background: AlarmReceiver/BootReceiver · NovaNotificationService(MediaHub) · WakeWordService   │
│   └─ Assistant: VoiceInput → IntentRouter (offline) → AiClient (optional) → ActionValidator → execute │
│ DataHub (in-memory snapshot) ← providers;  Look (palette + night) ← Prefs (SharedPreferences/JSON) │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
          │ HTTPS, Bearer token                              │ HTTPS (PKCE)          │ HTTPS/LAN + token
          ▼                                                  ▼                       ▼
  Cloudflare Worker /v1/chat ─► Gemini or Workers AI   Spotify Web API        ESP32 / Raspberry Pi
```

## Packages (`app/src/main/java/com/nova/`)

| Package | Responsibility (spec §5.1 module) |
|---|---|
| `core` | `StandbyHost` gesture router & families (core), `Pages`, `Overlays`, `DataHub`, `NightController` |
| `pet` | `PetBrain` state machine, `PetView` renderer (springs, adaptive fps), `Expression` table |
| `audio` | `SoundFx` synthesised sounds + `SoundLimiter` |
| `clock` | `ClockFaceView` gallery (digital, analog, minimal, flip, NØVA, seconds) |
| `widgets` | `WidgetRenderer` (12 widgets), `Layouts` (packer, saved layouts, schema), `TileGridView` (direct editing) |
| `photos` | `PhotoFrameView` (SAF folder, EXIF, down-sampling, overlays, ambient colour) |
| `music` | `MusicView`, `Spotify` (PKCE, Connect devices, transfer, search-and-play) |
| `integrations` | `Weather` (Open-Meteo), `SystemStatus`, `CalendarSource`, `Alarms`, `NotificationHub`/`MediaHub`, `Net` |
| `voice` | `VoiceInput`, `Speaker` (TTS), `Assistant`, `AssistantView`, `WakeWord` (Vosk) |
| `ai` | `AiClient` (Worker client; response parsing + validation) |
| `actions` | `ActionType` allowlist, `ActionValidator`, `IntentRouter` |
| `home` | `SmartHome` adapter |
| `settings` | `SettingsScreen` framework, `SettingsPages`, `AppearancePages`, `ColorPickerView`, `ThemePreviewView` |
| `storage` | `Prefs` (typed SharedPreferences, change listeners) |
| `ui` | `Palette`/`ColorRole`/`ThemePreset`/`Look`, `Theme` (fonts, night maths) |

## Key design decisions

- **One renderer per surface, Canvas only.** No bitmaps for UI, no blur filters, no AndroidX UI — keeps the
  MT6737T cool. Frame rates adapt: 30 fps when animating, 25 idle, 15 calm/sleepy, 8 asleep, 1 Hz clocks,
  0 when hidden.
- **Colours are roles, not constants.** Every renderer calls `Look.c(ColorRole.X)`, so presets, the custom
  theme editor, Photo Ambient and night palettes apply everywhere at once.
- **Gesture precedence.** `StandbyHost` only intercepts movement that exceeds 1.5× touch slop; children
  claim gestures first with `requestDisallowInterceptTouchEvent` (edit mode, widget stacks, colour picker).
- **Layouts are data.** `LayoutBook` JSON with a schema number; unknown widgets/sizes from newer versions are
  skipped, corrupt data falls back to defaults. Direct editing and the Settings editor write the same store.
- **AI output is untrusted.** Worker validates against the allowlist, the phone validates again
  (`ActionValidator`), consequential actions (`SPOTIFY_TRANSFER`, `SMART_HOME`) require an on-screen "Yes".
- **Foreground-only providers.** Broadcast receivers and sensors register in `onResume` and stop in
  `onPause`. Only alarms, the notification listener (user-enabled) and the optional wake-word service run
  in the background.

## Cloud

`cloudflare-worker/src/index.js` — `GET /v1/health`, `POST /v1/chat`; token auth (`NOVA_ACCESS_TOKEN`
secret, constant-time compare), rate-limit binding (20/min per installation), 500-char input cap, Gemini
when `GEMINI_API_KEY` is set else Workers AI, JSON-only replies re-validated, no content logging.
