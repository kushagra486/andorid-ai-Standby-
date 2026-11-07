# Troubleshooting

| Problem | Fix |
|---|---|
| APK won't install over an older one | Builds before v0.2.0 had a different signature — uninstall NØVA once. If you switch to your private release key, uninstall once too. |
| "App not installed" on the phone | Settings → Security → allow *Unknown sources* for your file manager. |
| Mic button says speech isn't available | Install/enable the Google app and its voice typing; for offline speech download the language in Google voice settings. |
| "I didn't catch that" a lot | Speak after the chirp; quieter room; try English (India) in Settings → Voice. |
| Music page says "Enable notification access" | Settings → Music → Open notification access settings → enable NØVA. |
| Fire TV not in the device list | Open Spotify on the Fire TV first; both must use the same account; Premium required for remote control. |
| Spotify sign-in fails | Redirect URI in the Spotify dashboard must be exactly `nova-zenfone://spotify-callback`. |
| Cloud AI: "rejected the access token" | The token in Settings must equal the Worker's `NOVA_ACCESS_TOKEN` secret. |
| Cloud AI: "limit reached" | Free quota or the 20/min limit; local commands still work. |
| Weather "unavailable" | Check internet; Settings → Weather → Refresh now. |
| Night mode never turns on | Settings → Display & night → Mode = Automatic and check Starts/Ends. |
| Light sensor test fails | Many phones expose no usable light sensor; the schedule is used instead. |
| Screen turns off | Settings → Display & night → Keep screen on = Always (or While charging). |
| Phone gets warm | NØVA dims automatically above 45 °C; use Animation = Calm and avoid charging in hot places. |
| Something crashed | Settings → About → Diagnostics shows the last crash; send it with your report. |
