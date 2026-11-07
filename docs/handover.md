# Handover — rebuild, install, update

## Get an APK
* **Releases page** (each version): `NOVA-v<version>.apk` (+ `-release-signed.apk` once you add your key).
* **Any push**: Actions → *Build APK* → artifact `NOVA-v<version>-apk`.

## Ship a new version
1. Change code (or ask Claude to).
2. In `app/build.gradle.kts` bump `versionCode` (+1) and `versionName`.
3. Merge to `main` → GitHub builds, tests and publishes the Release automatically.

## Build locally (no IDE)
JDK 17+, Android SDK platform 35 → `./gradlew testDebugUnitTest assembleDebug`.
Android Studio: File → Open → this folder → Build → Build APK(s).

## Your private signing key (recommended for the final install)
1. On your PC: `scripts/make-release-key.sh` → creates `nova-release.jks`. **Back it up** (and the password).
2. GitHub → Settings → Secrets and variables → Actions → add
   `NOVA_KEYSTORE_BASE64` (= `base64 -w0 nova-release.jks`) and `NOVA_KEYSTORE_PASSWORD`.
3. Next build attaches `NOVA-v…-release-signed.apk`. Uninstall the debug-signed app once, install the
   signed one; from then on always install the `-release-signed` APK.

## Cloud AI
See `cloudflare-worker/README.md`. Secrets live only in Cloudflare (`wrangler secret put`).

## Spotify
developer.spotify.com → Create app → Redirect URI `nova-zenfone://spotify-callback`, Android package
`com.nova.zenfone` → copy Client ID into NØVA → Settings → Music → Sign in.
