# CastBridge — Privacy Policy

CastBridge streams the audio playing on your Android phone to AirPlay 2 speakers
on your local network. This policy explains exactly what the app does with your
data.

**In short: nothing leaves your device or your local Wi-Fi.**

## What the app handles

- **Audio.** With your explicit consent — Android's capture prompt, shown every
  time you start casting — CastBridge captures the audio playing on your phone
  and streams it to the speaker you select. Audio is processed in memory, on your
  device and your local network only. It is never recorded to storage, and never
  transmitted to the developer or any third party.
- **Local network information.** To find speakers, the app discovers AirPlay
  devices advertised on your Wi-Fi network (mDNS/Bonjour). This stays on your
  device.
- **Settings.** The speaker you pick is remembered in the app's local
  preferences on your device.

## What the app does not do

- No accounts and no sign-in.
- No analytics, no advertising, no tracking of any kind.
- No crash reporting or diagnostics transmitted anywhere.
- No data collection, no uploads, no sharing. The app has no servers.

## Permissions, and why each is needed

- **`INTERNET`** — the AirPlay 2 sender opens a direct connection to the
  receiver on your local network. Android requires this permission for any
  network socket, including traffic that never leaves your LAN. The app does
  not use it for anything else: there is no code in it that contacts a remote
  host.
- **`MODIFY_AUDIO_SETTINGS`** — to read and control the phone's media volume and
  to route volume-key presses to the speaker while casting.
- **`POST_NOTIFICATIONS`** — to show the ongoing casting notification and its
  controls.
- **`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION`** — casting
  continues while the app is in the background; Android requires a foreground
  service of this type for screen/audio capture.
- **MediaProjection consent** — the platform's own capture permission, which you
  grant each time you start casting. It is not a manifest permission and cannot
  be granted once and kept.

The app does **not** request `RECORD_AUDIO`: playback capture does not require
it, and asking for it would be misleading.

## Children

The app is not directed at children and collects no data from anyone.

## Changes

If this policy changes, the updated version will be published with the app's
source code in this repository.