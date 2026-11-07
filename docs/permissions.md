# Permissions — what NØVA asks for and why

| Permission | When requested | Used for |
|---|---|---|
| Internet, network/Wi-Fi state | install (normal) | weather, cloud AI, Spotify, Wi-Fi widget |
| Vibrate, wake lock, boot completed | install (normal) | haptics; re-arming timers/reminders after reboot |
| Schedule exact alarm | install / Android 12+ setting | timers and reminders on time |
| Foreground service (microphone) | install (normal) | only while the optional wake word is on (notification shown) |
| **Microphone** | first time you tap the mic | touch-to-talk; wake word (on-device only) |
| **Calendar (read)** | Settings → Notifications & calendar | "Up next" widget; never writes |
| **Bluetooth connect** (Android 12+ only) | Settings → Music | Bluetooth widget / audio route |
| **Notifications** (Android 13+ only) | Settings → Alarms | timer/reminder alerts |
| **Notification access** (special) | Settings → Music / Notifications | music control & selected notifications |
| Photo folder (document tree) | Settings → Photo frame | reading only the folder you pick |

No location permission is used (weather is by place name). Nothing is uploaded except: the text you send
to your own Cloud AI Worker, the place name to Open-Meteo, and Spotify API calls you trigger.
