# Contributing to CastBridge

Thanks for looking at the code. Bug reports, patches and protocol notes are
all welcome.

## Reporting issues

- **Bugs**: open a GitHub issue. Include the app version (*About & licenses*
  in the app), device model / Android version, and logcat output if you have
  it (`adb logcat -s raop CastBridge`). Filter first, the tag matters.
- **Security**: do not open a public issue — see [SECURITY.md](SECURITY.md).
- **Receivers**: this lives or dies on device compatibility. A report that
  "works with my HomePod mini / Sonos One / Apple TV" is genuinely useful;
  so is a broken connect with the `raop` logcat tag.

## Building

See [README.md](README.md) "Build from source". Everything is in the
repository: the AirPlay sender is vendored under `airplay2-sender-cpp/` and
Mbed TLS is a pinned submodule, so a fresh clone needs no setup script.

```bash
git clone --recurse-submodules https://github.com/wesleydeland/castbridge.git
cd castbridge
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

Toolchain: JDK 17+, `compileSdk 36`, `build-tools 35.0.0`,
NDK `27.2.12479018`, CMake `3.22.1`. Only `arm64-v8a` is built.

## Where things live

| Path | What it is |
| --- | --- |
| `app/src/main/java/com/castbridge/` | All Java: UI, capture service, mDNS discovery |
| `app/src/main/cpp/native_sender.cpp` | JNI bridge: capture PCM into the sender's ring buffer |
| `airplay2-sender-cpp/` | Vendored, patched AirPlay 2 sender (see `NOTICE` for the patches) |
| `third_party/mbedtls` | Pinned submodule providing the crypto primitives |
| `fastlane/metadata/android/` | F-Droid / Google Play store listing |

The interesting protocol code is the vendored sender; the app itself is a
capture-loop, a JNI bridge and one screen of UI.

## Ground rules

- **Apache-2.0.** Any contribution is licensed under the same terms as the
  project (CLA not used; the Apache-2.0 notice covers it).
- **Match the local style.** Look at the file before editing it: the Java is
  plain `Activity`/`Service` code without frameworks on purpose, and the
  vendored C++ has its own voice that is deliberately left alone.
- **No new dependencies by default.** The zero-dependency, offline-capable
  build is a feature. Justify anything that adds one.
- **Privacy is a feature.** No analytics, no crash reporting, no network
  endpoints beyond the user's chosen receiver. A PR that phones home will
  not be merged.
- **Patches to `airplay2-sender-cpp/`** stay minimal and are documented in
  the root `NOTICE`, so the vendor copy stays diffable against upstream.

## Pull requests

1. Fork, create a branch, make the change.
2. `./gradlew assembleDebug` must pass; CI builds release and checks the
   16 KB page alignment of the native library.
3. Keep commits focused; describe *why*, not just *what*.
4. Screenshots or a short capture in the PR description help for UI changes.
