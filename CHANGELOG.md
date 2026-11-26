# Changelog

Every change gets a new version number. You can see the version in:
- **Home Assistant**: on the device page under *Firmware*, and the **Firmwareversie** sensor
- **The stream**: `http://<BOARD-IP>:8080/status` → `"version"`
- **The app**: at the bottom of the settings screen

| Version | Date | Changes |
|---|---|---|
| parent 1.0.7 | 2026-11-26 | Parent unit: per-room **bewaken** (monitor) switch in Home Assistant. Off = no alarm and no audio from that room; the setting survives a restart. |
| parent 1.0.6 | 2026-11-26 | Parent unit: I2S bus in `full_duplex` mode, so the speaker (always running) no longer blocks the microphone, and the ES8311 gets one shared clock. The **Test geluid** button logs the speaker, codec and amplifier state. |
| parent 1.0.5 | 2026-11-26 | Parent unit: **Test geluid** button (short RTTTL tone) and DEBUG logging for the speaker, mixer and codec, to find out why there is no sound. |
| parent 1.0.4 | 2026-11-26 | Parent unit: patched local `cst9220` component. It retries setup up to 15 times (the touch controller shares its reset with the display and may still be booting), continues with a warning on an unexpected check code or chip ID, and shows the check code in `dump_config`. New **Herstart** (restart) button. |
| parent 1.0.3 | 2026-11-26 | Parent unit: logger uses `USB_CDC`, because the USB-C port of the ESP-Mosaico is USB-OTG (USB-Serial-JTAG is only on the header). |
| parent 1.0.2 | 2026-11-26 | Parent unit: ES8311 codec is at I2C address 0x19 on the ESP-Mosaico (ESPHome default is 0x18), so audio failed to initialise. |
| parent 1.0.1 | 2026-11-25 | Parent unit: fix black screen. Drive GPIO60 (active-low supply for display, codec and modules) low before anything else starts. Start the touch controller after the display, because they share the reset line (GPIO44). Codec starts after the supply is on. |
| 2.4.0 | 2026-11-18 | **Parent unit** on ESP-Mosaico (ESP32-S31): listens to two baby monitors at once, touchscreen with status/level/mute/push-to-talk per room, alarm (tone + vibration + red screen) when a monitor drops out, volume/screen/battery in Home Assistant. New `babyfoon_receiver` component. Requires ESPHome 2026.11 and ESP-IDF 6.1.1. CI now compiles both firmwares. |
| 2.3.0 | 2026-11-07 | **Access key**: the stream, push-to-talk, tune and status endpoints require a secret key (min. 24 characters, header `X-Babyfoon-Key` or `?key=`). Constant-time comparison, 1 s delay per wrong attempt, and per-IP lockout after 5 failures (1, 2, 4 … up to 60 min). App: key field per baby monitor and clear error messages. |
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
