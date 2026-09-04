# Stereo Analog Recorder

**Stereo Analog Recorder** is an Android app that lets you **measure and control the microphone input gain** in real time, **record** it to a file, and **adjust the headphone listening volume** of the mics while "Live listen" is on — all so loud sources never clip at 0 dB and your recordings stay clean.

The main goal is simple: give you precise control over microphone sensitivity — both **boost** (when the source is too quiet) and, more importantly, **attenuation** (when the source is loud and would otherwise distort). As a bonus, the same gain setting also keeps your recordings clean when you open the camera, record a voice memo or do a WhatsApp / Telegram call, because the gain is applied at the codec (root) or DSP layer **before** the samples leave the app process — and is left in place by the always-on capture that survives the user switching apps.

## Installation scripts

This repository ships **one** install script:

- **[`install-android-with-build-alsa-driver.sh`](install-android-with-build-alsa-driver.sh)** — *Developer build + install script*. Cross-compiles `tinymix` from the bundled tinyalsa source (via the NDK's clang toolchain) **for every supported ABI** and writes the binaries straight into the APK assets folder, then builds the debug APK with Gradle (via the Android SDK) so the freshly-compiled `tinymix` ends up in the APK, and finally installs the APK on the connected device and grants runtime permissions. Both rely on a locally-installed Android SDK + NDK — Gradle uses the SDK, the C/C++ build uses the NDK. Requires JDK 17+, Android SDK with `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`, and an NDK (r27 family).

For users who don't want to rebuild anything: grab the latest APK from [Releases](../../releases) and install it with `adb install -r app-debug.apk`. **The APK is already self-contained**: the `tinymix` binary for all four ABIs is packaged as an asset, and the app extracts it automatically into its private files directory on first launch.

---

## Bundled dependencies

The repository only carries the source needed to build the `tinymix` binaries:

| Dependency | Version | Source |
|---|---|---|
| `tinymix` / `libtinyalsa` (source) | tinyalsa **2.0.0**, commit `9fab97c` (master, 2026-07-27) | [github.com/tinyalsa/tinyalsa](https://github.com/tinyalsa/tinyalsa) |

The final APK, with the `tinymix` binaries for all four ABIs bundled in, is published to [GitHub Releases](../../releases) on every release. The build script compiles `tinymix` from source for the 4 supported ABIs (arm64-v8a, armeabi-v7a, x86_64, x86), drops them as assets into the APK, builds the APK, and installs it — without relying on any prebuilt binaries committed to the repo.

License details for the bundled third-party components (BSD-3-Clause for `tinymix` / `libtinyalsa`, Apache-2.0 for AndroidX and Google Material) are in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

---

## What the app lets you do

### Per-microphone gain control
- Two independent horizontal sliders: **Mic 1** and **Mic 2**, mapped to the left and right channels of a stereo microphone capture.
- Each slider controls gain in **dB**, with positive values to boost the signal and negative values to attenuate it.
- A **Link** switch moves both mics together with a single shared gain value, so you can adjust them in lockstep when needed.
- The maximum boost and attenuation range is configurable from settings (the gain scale slider).

### Two-tier gain architecture
- **Analog pre-ADC attenuation (ALSA / tinyalsa)** — on rooted devices, the app lowers the codec preamp gain *before* the analog-to-digital converter. This is the only way to stop clipping at the source, because anything digital-only would still be applied to already-clipped samples.
- **Digital DSP gain** — a multiply-and-clamp stage applied to the captured PCM data, used as a fallback when root or the ALSA helper is not available.
- The status line in the UI clearly tells you which path is active (analog + digital, or digital only).

### Real-time monitoring
- Two **level meters**, one per channel, showing the live dB level of each mic while a recording is running.
- Two selectable **meter styles**:
  - **Digital (DAW-style)**
  - **Analog (tape-style)**
- **Per-channel peak indicator** (numeric, in dB) that remembers the highest level reached since the recording started.
- A **CLIP** marker lights up whenever a channel hits 0 dB.
- An **elapsed-time counter** while recording.

### Live headphone monitoring
- A "Live listen" toggle that lets you hear the microphones through connected wired or Bluetooth headphones, even when you are not recording. Useful for checking what the mics are picking up in real time.
- A **Listen volume** slider (-100 % … +100 %, 0 % = unity) appears the moment "Live listen" is on and headphones are connected. Negative values attenuate the listening volume while positive values amplify it. A second **Max range** slider underneath (0 – 75 dB, default ±20 dB) sets the dB reach at the volume slider's extremes: the linear gain applied to the monitor tap is `10^((volumePercent / 100) × (maxDb / 20))`. Both values are persisted in `SharedPreferences` and restored on relaunch.
- The gain is delivered in two stages: the part that fits inside `AudioTrack.setVolume()` (per-instance, channel-symmetric, **does not touch the system media / ring / call / alarm volume**, and does not touch the recording going to disk) is fed to `setVolume`; the leftover boost is applied as a per-sample buffer multiplication inside the monitor tap with rounding + 16-bit clamping. This two-stage split is what makes boost above unity actually audible on devices whose `AudioTrack.getMaxVolume()` is capped at 1.0 (every modern Android) — `setVolume` alone would clip silently at unity on those devices.

### Recording
- One-tap **Record** / **Stop** buttons (the classic red circle and white square).
- Three output formats:
  - **WAV 16-bit** (uncompressed PCM).
  - **WAV 24-bit** (uncompressed PCM, higher dynamic range).
  - **M4A (AAC)** — compressed, with a selectable bitrate from the device's supported range (typically 32–320 kbps).
- **Sample rate** is auto-detected from what your phone can actually capture, so the dropdown only lists rates that are guaranteed to work end-to-end.
- Recordings can run in the background with a persistent notification that exposes quick −/+ per-mic controls and a start/stop action.

### Settings and personalization
- **Theme**: Light or Dark, applied app-wide, including the notification.
- **Language**: English or Italiano, fully translated UI.
- **Gain scale**: choose the maximum ± dB range used by the sliders.
- **Control type**: choose between real analog **Gain** (root only, the only path that truly prevents clipping) and **Level** (digital volume scaling that works on every device).
- **Notification style**: classic controls (−/+ per mic, rec/stop) or a minimal "recording in background" notification.

### Gain stays in effect across apps (root path)
When **Control type = "Gain (root only)"** is selected and root is available, the requested dB is split into an analog portion applied *before* the ADC (ALSA, via the bundled `tinymix` helper) and a residual digital portion applied to the PCM. The codec value is latched at the hardware level and the residual is applied by the always-on foreground capture. **As long as the app is left running in the background**, both numbers remain at whatever level you dialed them to — opening another app that records from the microphone (the stock camera with audio on, a voice memo, WhatsApp or Telegram audio messages, WhatsApp or Telegram voice / video calls, …) will keep capturing at your gain: a quiet source you lifted with **+12 dB** stays lifted, a loud concert source you cut with **−18 dB** stays cut. The classic-controls foreground notification keeps working too, so you can fine-tune both mic gains up or down from the shade without leaving the other app. Stop the foreground service from the notification (or close Stereo Analog Recorder from the app) and the codec is restored to its boot default — your other apps go back to their normal mic level.

### Warning
- The app reminds you that large positive gains can damage your hearing and distort the recording. With loud sources, the recommended approach is **negative gain** to stay below 0 dB.

---

## How it works at a glance

| Step | What happens |
|---|---|
| Capture | `AudioRecord` reads the two internal mics as a stereo PCM stream (L = Mic 1, R = Mic 2). |
| Analog stage (root only) | The requested dB is split: the part the codec can deliver is applied *before* the ADC via ALSA; the residual stays for digital. |
| Digital stage | The residual dB is multiplied into the PCM samples and clamped. |
| Meters / peak | Each buffer's level is converted to dB and pushed to the meters and the peak memory. |
| Recording | The processed PCM is encoded to the chosen format and written to disk. |
| Live listen | A second consumer (`AudioTrack`) taps the same processed PCM in parallel and plays it through the connected headphones. The headphone level is split into an `AudioTrack.setVolume()` portion (per-instance, both channels together, no system stream volume touched) plus a per-sample buffer multiplier for any boost that exceeds the platform's `AudioTrack.getMaxVolume()`. |
| Always-on capture | While the foreground service is running (i.e. you haven't killed it from the notification, exited the app, or stopped the service), the AudioRecord and the ALSA codec state stay alive in the background — so any other app that opens the microphone inherits the gain you've dialed in. |

---

## Compatibility

- **Android 8.0 (API 26)** or later.
- Works on any phone with two microphones, no root required for the digital path.
- **Root (Magisk recommended)** is required for the analog pre-ADC attenuation that prevents clipping at the source.
- "Live listen" requires a wired headset, wired headphones, USB headset, or Bluetooth A2DP output. The listen-volume slider can boost up to the chosen **Max range** (0 – 75 dB) on every device — the per-sample buffer multiplier in the monitor tap takes over for any gain that `AudioTrack.setVolume()` can't express (typically above 1.0), so positive volume values are always audible regardless of the platform's `getMaxVolume()` cap. The AudioManager stream volume is never modified.

---

## Project info

- Language: **Kotlin**
- Min SDK: 26 — Target SDK: 34
- Package: `com.stereoanalogrecorder.app`


---

## License

Copyright (C) 2026 Luigi De Paola

Stereo Analog Recorder is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License v3.0 (GPL-3.0).

