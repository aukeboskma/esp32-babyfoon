# Babyfoon Android app

One app for the baby monitor audio and two Reolink cameras. Works **only on your own Wi-Fi** (no cloud, no remote access).

> The app's user interface is in Dutch.

## Features

- **Listen**: live audio from the baby monitor board. Keeps running with the screen off (foreground service).
- **Level meter**: see whether there is sound, even with playback muted.
- **Sound notification**: when the level exceeds the threshold.
- **Connection-loss alarm**: after 10 s without audio data, an alarm plays on the **alarm volume** — also when the phone is on silent. Tap "Gezien" (seen) to silence it; it re-triggers after 5 minutes if the connection is still down.
- **Push-to-talk**: hold the button and speak; your voice plays softly through the speaker in the nursery. Listening is muted while you talk to avoid feedback. The app asks for microphone permission the first time.
- **Noise gate and playback gain**: adjustable in the settings.
- **Cameras**: two tiles with a still image refreshed every 3 s. Tap for live video (RTSP). For the Reolink E1 Zoom, **hold** the arrow and zoom buttons to pan, tilt and zoom.

## Install

**Easiest:** download the APK from the [latest release](https://github.com/aukeboskma/esp32-babyfoon/releases/latest) on your phone and open it. Allow "install unknown apps" for your browser once. Newer versions install over the old one and keep your settings.

## Build it yourself (optional, about 20 minutes)

1. **Install Android Studio**: <https://developer.android.com/studio>
2. **Open the project**: choose *Open* and select the `android-app` folder. Wait until "Gradle sync" finishes (the first time downloads a lot).
3. **Enable developer options on your phone**: *Settings → About phone* → tap *Build number* 7 times. Then enable **USB debugging** in *Developer options*.
4. **Connect your phone** with a USB cable and allow the connection on the phone.
5. **Click ▶ Run** in Android Studio. The app installs and starts.

If Android Studio offers to update the Gradle plugin or Kotlin version, that is fine — choose *Update*.

## Configure

1. Tap the **gear icon** (top right).
2. **Baby monitor**: enter the board's IP address and tap *Test babyfoon*. Give the board a **fixed IP address** (DHCP reservation) in your router.
3. **Cameras**: name, IP address, username and password, then *Test camera*.
   - Create a **separate view-only user** in the Reolink app.
   - **HTTP** and **RTSP** must be enabled on the camera: *Reolink app → Settings → Network → Advanced → Port settings*.
4. Tap **Batterijbesparing uitzetten** (disable battery optimization). On Samsung, Xiaomi and OnePlus, also set the app's battery usage to **Unrestricted**.
5. Tap **Opslaan** (save).

Passwords are stored encrypted on the phone.

## Test before you rely on it

- Talk near the board: the meter moves.
- **Unplug the board**: the alarm sounds within about 10 seconds.
- Repeat with the **phone on silent**: the alarm still sounds.
- Run it overnight with the screen off: no false alarms.

If any of these fail, don't use the app as your only baby monitor.

## Notes

- Up to **two phones** can listen at the same time.
- Camera audio is muted by default so you keep hearing the baby monitor.
- Expected audio latency is about 0.3–0.5 s.
