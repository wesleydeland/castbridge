# HomePod cast — Android app

Companion app to the host bridge: captures system audio on the phone
(`AudioPlaybackCapture`) and streams it to an AirPlay 2 receiver via the
patched `airplay2-sender-cpp` library (NDK build) — **no host machine
involved**.

Status: working spike — validated end-to-end against Symfonium + Pocket Casts
with a HomePod mini.

What works:

- captures any app that allows playback capture (Symfonium and Pocket Casts do;
  DRM/streaming apps that opt out stay silent — same as every app on the Play
  Store that does this)
- mutes the phone automatically while casting (capture is pre-volume, so the
  mute does not affect what the HomePod hears)
- phone volume keys drive the **HomePod's** volume (remote-volume MediaSession)
  with on-screen fallback buttons
- mDNS discovery of AirPlay receivers (`_airplay._tcp`), remembers the last one

## Build

1. From the repository root, run `./setup.sh` once — it clones + patches +
   builds `ap2-sender/`, which this app's CMake needs
   (`RAOP_SRC` defaults to `../../../../../ap2-sender` relative to `cpp/`).
2. `cd android && ./gradlew assembleDebug`
   Requirements: JDK 17+, Android SDK with `ndk;27.2.12479018` and
   `cmake;3.22.1`, and `local.properties` containing
   `sdk.dir=/path/to/android-sdk` (copy the path from your SDK install).
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Use

Open the app → **Find HomePod (mDNS)** (or type the IP) → **Start casting** →
accept the one-time capture consent → play something in your music/podcast app.

## Notes / limitations (spike scope)

- One capture-consent tap per session (Android requires it).
- arm64-v8a only; minSdk 29 (Android 10).
- No auto-reconnect if the receiver drops mid-session — stop and start again.
- If the phone's volume keys end up adjusting the local volume instead, another
  app's media session currently owns the keys — use the in-app buttons.
