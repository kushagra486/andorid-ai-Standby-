# Testing & acceptance

## Automated (run on every push by GitHub Actions)

| Suite | What it covers |
|---|---|
| `PetBrainTest` (13) | greeting, sleepy/sleeping timers, night timers, wake reactions, annoyed after 7 taps, priorities, error status, activities, idle behaviours are silent |
| `SoundLimiterTest` (2) | ≥200 ms gap, ≤10 sounds/min |
| `LayoutsTest` (8) | no overlaps, overflow, gap filling, full size, JSON round-trip, future-schema tolerance, corrupt data, duplicate/remove |
| `ActionsTest` (11) | allowlist, extra args, links/code rejected, ranges, confirmation flags, offline commands, every routed action validates |
| `NightRulesTest` (4) | schedule across midnight, overrides, 2-minute dark-room rule, hysteresis |
| `RobolectricUnitTests` (8) | palette JSON & presets, Open-Meteo parsing, Spotify PKCE (RFC 7636 vector) & device matching, smart-home address rules, wake-word sensitivity, AI reply validation |
| `ScreenshotTest` | boots the real `MainActivity`, renders 37 screens at 1280×720 into `app/build/screenshots/` (CI artifact) |
| `cloudflare-worker` (10) | validation, JSON extraction, request limits, token compare, auth, rate limit, provider errors |

Run locally: `./gradlew testDebugUnitTest` and `cd cloudflare-worker && npm test`.

## On-device acceptance (ZenFone 3 Max ZC520TL)

Tick these on the phone — they cannot be proven by automated tests.

- [ ] **A** Install `NOVA-v1.0.0.apk`; launches in landscape, full screen.
- [ ] **B** Face looks right; tap reactions, haptics, chirps; Settings → Test all expressions plays all 14.
- [ ] 30-minute idle: no crash, gets sleepy (5 min) and sleeps (15 min); phone not hot.
- [ ] Swipe left/right through all pages; swipe up/down changes clock face / layout / photo style without changing page.
- [ ] Dashboard: long-press → jiggle; drag, resize (blue badge), remove (orange badge), + Add, Done; restart app → layout kept.
- [ ] Settings → Layouts edits match the on-screen dashboard.
- [ ] **C** Turn Wi-Fi off/on: weather shows "offline — last update", recovers; rotate/background/foreground OK.
- [ ] Photo frame: choose a folder with portrait + landscape + large photos; slideshow, tap left/right/middle.
- [ ] Night: set Mode = Always on → red; try Dim cyan / Near-black; Test light sensor; back to Automatic.
- [ ] Plug in charger with "When charging, show: Clock" → switches to clock.
- [ ] **D** Microphone: tap mic, say "set a timer for 1 minute" → timer widget counts, alarm fires; deny permission → message; "Type instead" works.
- [ ] **E** Cloud AI (after Worker deploy): Test connection OK; ask a general question; "turn on desk lamp" asks for confirmation.
- [ ] **F** Notification access on → play Spotify → Music page shows track; play/pause/next; Bluetooth speaker → "Playing on: Bluetooth …"; NØVA dances.
- [ ] **G** Spotify Client ID + sign-in → Music → Change → Fire TV → playback moves (Premium), or note the limitation.
- [ ] **H** Wake word: download model, enable, 1 hour on charger → check Settings → Wake word benchmark and false triggers.
- [ ] **I** Owner release key created, secrets added, signed APK installs and updates in place.
