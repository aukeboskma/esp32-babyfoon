# ESP32 Baby Monitor

A DIY baby monitor built on a cheap ESP32-S3 voice board (≈ €15), integrated with **Home Assistant** and paired with its own **Android app**. Everything runs **locally on your own Wi-Fi** — no cloud.

> ⚠️ **Not a medical device.** This is a hobby project. Test it thoroughly (see [Testing](#testing)) and keep a regular baby monitor as a backup at first.

---

## Features

### On the board (ESPHome)
| Feature | Description |
|---|---|
| **Live audio** | The microphone is streamed live at `http://<IP>:8080/audio.wav`. Listen in the app or in VLC. Up to 2 listeners at a time. |
| **Sound level** | Home Assistant sensor (dBFS), updated every second. |
| **Sound detected** | Binary sensor with an adjustable **threshold** (slider in HA). Use it for notifications and automations. |
| **Access key** | Nobody on your network can listen, talk or tune without the secret key. Wrong guesses are slowed down and the device is locked out after 5 attempts. |
| **Hum filter** | A 4th-order high-pass filter (default 150 Hz) removes mains hum and low rumble from the stream. Crying and voices are not affected. |
| **Noise tune** | Press *Ruis inmeten* (in HA, in the app, or hold the Wi-Fi button 3 s): the board listens for 10 s and sets the noise threshold (+5 dB) and sound threshold (+10 dB) itself. Short noises during the measurement are ignored; the sound threshold is never set above −25 dB. |
| **Noise gate** | Below the **noise threshold**, the board sends silence instead of hiss. Above it, sound passes immediately. Switch and threshold in HA. |
| **Push-to-talk** | Your voice from the app plays softly through the speaker, with its own **talk-back volume** slider. The stream is muted while you talk (no feedback). |
| **Speaker / white noise** | Media player in HA for white noise or lullabies. **Volume** slider in HA, kept in sync with the physical buttons. Maximum volume is capped. |
| **Buttons** | Volume +, Volume −, and the Wi-Fi button (short press: stop playback; hold 3 s: noise tune). |
| **Diagnostics** | IP address, Wi-Fi signal, uptime, firmware version, number of listeners. |

### Android app
| Feature | Description |
|---|---|
| **Multiple monitors** | Add as many baby monitors as you like (e.g. one per child). Listen to all of them at once and mute each one separately. |
| **Listen** | Live audio, keeps working with the screen off (foreground service). |
| **Level meter** | See whether there is sound, even with playback muted. |
| **Sound notification** | When the level exceeds the threshold. |
| **Connection-loss alarm** | If any monitor sends no audio for 10 s, an alarm names it and plays on the **alarm volume** — even when the phone is on silent. |
| **Push-to-talk** | Hold the button on a monitor's card to talk to that room. |
| **Noise gate + gain** | Adjustable in the app. |
| **Cameras** | Any number of Reolink cameras: still image on the home screen, live RTSP video with one tap. Pan/tilt/zoom for the E1 Zoom. |

The app's user interface is in Dutch. See [`android-app/README.md`](android-app/README.md).

### Extras
- **Home Assistant automations** (`ha_automations.yaml`): notifications for sound, for the device going offline, and for a frozen measurement.
- **Enclosure** (`case/`): 3D-printable, parametric OpenSCAD design. Dimensions are still estimates — measure your board first.

---

## What you need

- **Board**: "Xiaozhi AI Voice Chat" ESP32-S3 module (AliExpress/eBay), PCB marked `ESP32S3-AI_V2.3` or `V2.4` (`303ESP32AI2`). It has an INMP441 microphone, a MAX98357A amplifier, and comes with a speaker.
- **A USB-C cable that carries data**, and a decent 5 V power supply.
- **Home Assistant** with the **ESPHome** add-on (ESPHome 2026.5 or newer).
- **Windows**: the [CH340 driver](https://www.wch-ic.com/downloads/CH341SER_EXE.html).
- For the app: an Android phone (Android 10 or newer).

---

## Installation

### 1. The board (ESPHome)

1. **Copy the stream component to Home Assistant.** Put the `components/audio_stream/` folder in `/config/esphome/components/audio_stream/`, for example via the *Samba share* add-on (`\\homeassistant.local\config\esphome\`):
   ```
   config/esphome/
   ├── secrets.yaml
   ├── babyfoon.yaml
   └── components/audio_stream/
       ├── __init__.py
       ├── audio_stream.cpp
       └── audio_stream.h
   ```
2. **Fill in your secrets.** Add the lines from `secrets.yaml.example` to your own `secrets.yaml`. You get an API key when you create a new device in ESPHome. Generate a **stream access key** (see [Security](#security)).
3. **Create the device.** ESPHome → *New device* → *Edit* → paste the contents of `babyfoon.yaml`.
4. **First flash over USB.** *Install → Manual download → Factory format*, then open [web.esphome.io](https://web.esphome.io) in Chrome or Edge → *Connect* → *Install* → select the file. Later updates go over the air.
5. **Check the logs.** You should see `Stream beschikbaar op ...:8080/audio.wav`, and the sound level should rise when you clap.
6. **Give the board a fixed IP address** in your router.
7. **Test with VLC.** *Media → Open Network Stream* → `http://<BOARD-IP>:8080/audio.wav?key=<YOUR-KEY>`. Under *Show more options*, set *Caching* to 150 ms for low latency.

### 2. Home Assistant settings

- Press **Ruis inmeten** while the room is quiet. The board sets **Ruisgrens** (noise threshold) and **Geluid drempel** (sound threshold) automatically. You can still fine-tune both sliders by hand.
- **Automations**: copy them from `ha_automations.yaml` and replace `notify.mobile_app_JOUW_TELEFOON` with your phone's notify service.

### 3. The Android app

**Easiest: download the APK.**
1. On your phone, open the [latest release](https://github.com/aukeboskma/esp32-babyfoon/releases/latest) and download `babyfoon-x.y.z.apk`.
2. Open the file. Android asks once to allow installing apps from your browser — allow it.
3. To update later, just download and install the newer APK over the old one; your settings are kept.

GitHub builds a new APK automatically every time the app code changes.

**Or build it yourself:** install [Android Studio](https://developer.android.com/studio), open the `android-app` folder, enable USB debugging on your phone and click **▶ Run**.

**Then:**
4. In the app (gear icon), enter the board's IP address and tap *Test babyfoon*.
5. Tap **Batterijbesparing uitzetten** (disable battery optimization). On Samsung, Xiaomi and OnePlus, also set the app to *Unrestricted*.

See [`android-app/README.md`](android-app/README.md) for camera setup.

---

## Testing

Do this before you rely on it:

- [ ] Talk near the board: the meter in the app moves.
- [ ] **Unplug the board**: the app alarm sounds within about 10 s.
- [ ] Repeat with your **phone on silent**: the alarm still sounds.
- [ ] Run the app overnight with the screen off: no false alarms.

---

## Security

The stream is meant for your **local network only** — never forward port 8080 on your router.

Every request (listening, push-to-talk, noise tune, status) needs the **access key** from `secrets.yaml`:
- The key must be at least 24 characters (letters, digits, `-` and `_`). A 48-character hex key (192 bits) is impossible to guess.
- Wrong keys are compared in constant time, delayed by 1 second, and after 5 failures from the same IP that device is locked out for 1 minute, then 2, 4, 8 … up to 60 minutes.
- The traffic itself is not encrypted (no TLS), so the key is visible to someone who can intercept traffic on your Wi-Fi. Put guests and untrusted smart devices on a **guest network**.

Generate a key on Windows (PowerShell):
```powershell
$b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ($b | % { $_.ToString('x2') }) -join ''
```
or on Linux/macOS: `openssl rand -hex 24`. Put it in `secrets.yaml` as `babyfoon_stream_key: "..."` and enter the same key in the app.

## How it works

- `components/audio_stream` is a custom ESPHome component. It reads the microphone through a `MicrophoneSource` (16 kHz, 16-bit mono). Each listener gets its own ring buffer and TCP task.
  - All endpoints require the access key (`X-Babyfoon-Key` header or `?key=`)
  - `GET /audio.wav`: endless WAV stream
  - `GET /status`: JSON with version, sample rate, listener count and thresholds
  - `GET /tune`: start the 10-second noise measurement
  - `POST /talk`: raw PCM (16-bit, 16 kHz) played on the speaker through an extra mixer input
- On a slow connection, the oldest audio is overwritten, so latency never builds up.
- A hum filter (two Butterworth biquads, 150 Hz) runs before the noise gate.
- The noise gate runs on the board (10 ms attack, 2.5 s hold, 400 ms release), so it works in any player.
- The app uses a non-blocking `AudioTrack` and a watchdog that raises an alarm when no data arrives for too long.

## Pinout (V2.3 / V2.4)

| Function | GPIO |
|---|---|
| Microphone WS / SCK / SD | 4 / 5 / 6 |
| Amplifier DIN / BCLK / LRC | 7 / 15 / 16 |
| Volume − / Volume + / Wi-Fi button | 39 / 40 / 1 |
| PIR header | 8 |

Pinout thanks to [immortalbob/303ESP32S3-AI-ESPHome](https://github.com/immortalbob/303ESP32S3-AI-ESPHome).

## Versions

See [`CHANGELOG.md`](CHANGELOG.md). The version is also shown in Home Assistant (*Firmware*), on `/status`, and at the bottom of the app settings.

## License

[MIT](LICENSE)
