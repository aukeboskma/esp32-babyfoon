# Changelog

Every change gets a new version number. You can see the version in:
- **Home Assistant**: on the device page under *Firmware*, and the **Firmwareversie** sensor
- **The stream**: `http://<BOARD-IP>:8080/status` → `"version"`
- **The app**: at the bottom of the settings screen

| Version | Date | Changes |
|---|---|---|
| 2.2.0 | 2026-11-05 | Firmware: hum filter on the stream (4th-order high-pass, default 150 Hz) removes 50 Hz mains hum and low rumble. Configurable with `high_pass:`. App unchanged. |
| 2.1.0 | 2026-11-05 | **Noise tune**: the board measures the room noise for 10 s and sets *Ruisgrens* (+5 dB) and *Geluid drempel* (+10 dB) itself. Start it from the "Ruis inmeten" button in Home Assistant, from the app, or by holding the Wi-Fi button for 3 s. The app now uses each board's own thresholds (new `/tune` endpoint, thresholds in `/status`). Requires firmware 2.1.0. |
| 2.0.0 | 2026-11-05 | App: any number of baby monitors and cameras (add/remove in settings). Listen to all monitors at once, mute each one separately, push-to-talk per monitor, alarm names which monitor lost its connection. Existing settings are migrated. No firmware changes. |
| 1.5.0 | 2026-11-05 | App: cameras now log in with a token, try HTTP and then HTTPS, and show a clear error message (wrong password, port closed, …). No firmware changes. |
| 1.4.0 | 2026-11-05 | The Android app is built automatically by GitHub Actions; download the APK from Releases. Fixed signing key so updates install over the previous version. No firmware changes. |
| 1.3.1 | 2026-11-05 | Documentation in English; radar integration removed (docs only — firmware and app stay 1.3.0) |
| 1.3.0 | 2026-11-05 | Version numbers, IP address in diagnostics, "Volume" and "Talk-back volume" sliders in Home Assistant |
| 1.2.0 | 2026-11-04 | Push-to-talk from the app |
| 1.1.0 | 2026-11-04 | Noise gate on the stream (threshold + switch in Home Assistant) |
| 1.0.0 | 2026-10-30 | First version: sound level, live stream, buttons, app, enclosure |
