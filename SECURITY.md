# Security Policy

## Supported versions

Only the latest release is supported. The app moves quickly and there are no
LTS branches.

## Reporting a vulnerability

Please report security issues privately rather than opening a public issue:
open a GitHub **security advisory** for this repository ("Report a
vulnerability" on the Security tab), or email the maintainer at
**wesley@wesleydeland.com** with details and, if possible, a reproduction.

You will get an acknowledgement within a week. Please include:

- the app version (from *About & licenses* in the app) and the commit you
  built from, if you build from source
- device / Android version
- what to do to see the problem, and what you expected instead
- logcat output if the failure is visible there

## What counts

The app captures playback audio (with the platform's explicit per-session
consent), streams it to an AirPlay 2 receiver the user picks on their local
network, and stops. Reporting flow that would matter:

- audio or metadata leaving the local network to anywhere but the receiver
- the pairing/encryption implementation (vendored `airplay2-sender-cpp`,
  Mbed TLS, or its use of them)
- the JNI boundary (`app/src/main/cpp/native_sender.cpp`)
- services/components exported or callable from outside the app

## What does not count

- "The app can capture audio" — that is its purpose, gated behind Android's
  MediaProjection consent prompt each session.
- Capturing apps that do not opt out. Opt-out enforcement (DRM apps staying
  silent) belongs to the platform, not to CastBridge.
- A pairing PIN shown while pairing is honored once and never triggers the
  stream anywhere.

## Scope notes

- The `INTERNET` permission exists for exactly one purpose: TCP/UDP to the
  receiver IP on standard AirPlay ports, on your LAN. Any domain, no
  telemetry, no update checks.
- Cryptographic primitives come from Mbed TLS (pinned submodule) and
  orlp/ed25519; the pairing protocol lives in `airplay2-sender-cpp/` (a
  patched copy of a third-party sender). Issues in those are best reported
  upstream — but if you found something in the patches, it belongs here.
