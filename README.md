# ESP32 Babyfoon

Een zelfgebouwde babyfoon op een goedkoop ESP32-S3-bordje (± €15), gekoppeld aan **Home Assistant** en met een eigen **Android-app**. Alles werkt **lokaal op je eigen wifi**, zonder cloud.

> *English summary: a DIY baby monitor on a cheap "Xiaozhi" ESP32-S3 voice board running ESPHome. Live audio stream (WAV over HTTP), noise gate, push-to-talk, Home Assistant sensors, and an Android app with connection-loss alarm and Reolink camera view. Local network only. Docs are in Dutch.*

> ⚠️ **Geen medisch apparaat.** Dit is een hobbyproject. Test het goed (zie [Testen](#testen)) en gebruik in het begin een gewone babyfoon als reserve.

---

## Functies

### Op het bordje (ESPHome)
| Functie | Uitleg |
|---|---|
| **Live geluid** | De microfoon wordt live doorgestuurd: `http://IP:8080/audio.wav`. Te beluisteren in de app of in VLC. Max. 2 luisteraars tegelijk. |
| **Geluidsniveau** | Sensor in Home Assistant (dBFS), elke seconde bijgewerkt. |
| **Geluid gedetecteerd** | Aan/uit-sensor met instelbare **drempel** (schuifje in HA). Voor meldingen en automations. |
| **Ruisonderdrukking** | Onder de **Ruisgrens** stuurt het bordje stilte in plaats van ruis. Boven de grens direct weer geluid. Aan/uit en grens instelbaar in HA. |
| **Terugpraten** | Je stem uit de app komt zacht uit de speaker. Eigen schuifje **Volume terugpraten**. Tijdens het praten wordt de stream stil (geen rondzingen). |
| **Speaker / witte ruis** | Media player in HA: speel witte ruis of een slaapliedje af. Schuifje **Volume** in HA, volgt ook de knoppen. Maximaal volume begrensd. |
| **Knoppen** | Volume +, Volume −, en de WiFi-knop (stopt het geluid). |
| **Diagnose** | IP-adres, wifi-signaal, uptime, firmwareversie, aantal luisteraars. |

### Android-app
| Functie | Uitleg |
|---|---|
| **Luisteren** | Live geluid, blijft werken met het scherm uit (voorgrondservice). |
| **Volumemeter** | Zie of er geluid is, ook als het geluid uit staat. |
| **Melding bij geluid** | Als het geluid boven de drempel komt. |
| **Alarm bij verbindingsverlies** | Na 10 s zonder geluidsdata gaat een alarm af via het **alarmvolume**, ook als de telefoon op stil staat. |
| **Terugpraten** | Knop ingedrukt houden om te praten. |
| **Ruisonderdrukking + versterking** | Instelbaar in de app. |
| **Camera's** | Twee Reolink-camera's: stilstaand beeld op het beginscherm, live beeld (RTSP) met één tik. Draaien en zoomen voor de E1 Zoom. |

### Extra (optioneel)
- **Home Assistant-automations** (`ha_automations.yaml`): meldingen bij geluid, bij offline en als de meting stil blijft staan.
- **Babyradar** (`babyradar_*.yaml`): koppeling met een Seeed MR60BHA2 60 GHz-radar voor ademhaling en hartslag zonder contact. *Alleen informatief.*
- **Behuizing** (`case/`): 3D-printbaar, parametrisch in OpenSCAD. Maten zijn nog geschat.

---

## Benodigdheden

- **Bordje**: "Xiaozhi AI Voice Chat" ESP32-S3-module (AliExpress/eBay), opdruk `ESP32S3-AI_V2.3` of `V2.4` (`303ESP32AI2`). Met INMP441-microfoon, MAX98357A-versterker en speaker.
- **USB-C-kabel die data kan versturen** en een goede 5V-adapter.
- **Home Assistant** met de **ESPHome**-add-on (ESPHome 2026.5 of nieuwer).
- **Windows**: de [CH340-driver](https://www.wch-ic.com/downloads/CH341SER_EXE.html).
- Voor de app: **Android Studio** en een Android-telefoon (Android 10 of nieuwer).

---

## Installeren

### 1. Het bordje (ESPHome)

1. **Kopieer het streamonderdeel naar Home Assistant.**
   Zet de map `components/audio_stream/` in `/config/esphome/components/audio_stream/`. Dat kan bijvoorbeeld via de *Samba share*-add-on: `\\homeassistant.local\config\esphome\`.
   ```
   config/esphome/
   ├── secrets.yaml
   ├── babyfoon.yaml
   └── components/audio_stream/
       ├── __init__.py
       ├── audio_stream.cpp
       └── audio_stream.h
   ```
2. **Vul je secrets in.**
   Zet de regels uit `secrets.yaml.voorbeeld` in je eigen `secrets.yaml`. Een API-sleutel krijg je als je in ESPHome een nieuw apparaat maakt.
3. **Maak het apparaat aan.**
   ESPHome → *New device* → kies *Edit* → plak de inhoud van `babyfoon.yaml`.
4. **Flash de eerste keer via USB.**
   *Install → Manual download → Factory format*. Open daarna [web.esphome.io](https://web.esphome.io) in Chrome of Edge → *Connect* → *Install* → kies het bestand. Latere updates gaan draadloos.
5. **Controleer de logs.**
   Je moet `Stream beschikbaar op ...:8080/audio.wav` zien, en het *Geluidsniveau* moet omhoog gaan als je in je handen klapt.
6. **Geef het bordje een vast IP-adres** in je router.
7. **Test met VLC.**
   *Media → Netwerkstream openen* → `http://IP-VAN-BORDJE:8080/audio.wav`. Zet onder *Meer opties* de *Caching* op 150 ms voor weinig vertraging.

### 2. Instellen in Home Assistant

- **Geluid drempel**: meet het niveau als het stil is (bijv. −45 dB) en zet de drempel ongeveer **10 dB hoger** (−35).
- **Ruisgrens**: net boven het stille niveau (bijv. −40).
- **Automations**: neem ze over uit `ha_automations.yaml` en vervang `notify.mobile_app_JOUW_TELEFOON`.

### 3. De Android-app

1. Installeer [Android Studio](https://developer.android.com/studio) en open de map `android-app`.
2. Zet op je telefoon **USB-foutopsporing** aan: *Over de telefoon* → 7× tikken op *Buildnummer* → *Ontwikkelaarsopties*.
3. Sluit je telefoon aan en klik op **▶ Run**.
4. Vul in de app (tandwiel) het IP-adres van het bordje in en druk op *Test babyfoon*.
5. Druk op **Batterijbesparing uitzetten**. Zet de app bij Samsung, Xiaomi en OnePlus ook op *Onbeperkt*.

Zie [`android-app/LEESMIJ.md`](android-app/LEESMIJ.md) voor de camera-instellingen.

---

## Testen

Doe dit voordat je erop vertrouwt:

- [ ] Praat bij het bordje: de meter in de app beweegt.
- [ ] **Trek de stekker eruit**: binnen ± 10 s gaat het alarm af in de app.
- [ ] Doe dezelfde test met je **telefoon op stil**: het alarm klinkt nog steeds.
- [ ] Laat de app een nacht draaien met het scherm uit: geen onterecht alarm.

---

## Hoe het werkt (technisch)

- `components/audio_stream` is een eigen ESPHome-component. Het leest de microfoon via een `MicrophoneSource` (16 kHz, 16 bit mono). Elke luisteraar krijgt een eigen ringbuffer en een eigen TCP-taak.
  - `GET /audio.wav`: oneindige WAV-stream
  - `GET /status`: JSON met versie, sample rate en luisteraars
  - `POST /talk`: ruwe PCM (16 bit, 16 kHz) naar de speaker, via een extra ingang op de mixer
- Bij een trage verbinding wordt de oudste audio overschreven. De vertraging loopt dus niet op.
- De app gebruikt `AudioTrack` (niet-blokkerend) en een waakhond die alarm geeft als er te lang geen data binnenkomt.

## Pinout (V2.3 / V2.4)

| Functie | GPIO |
|---|---|
| Microfoon WS / SCK / SD | 4 / 5 / 6 |
| Versterker DIN / BCLK / LRC | 7 / 15 / 16 |
| Volume − / Volume + / WiFi-knop | 39 / 40 / 1 |
| PIR-aansluiting | 8 |

Pinout met dank aan [immortalbob/303ESP32S3-AI-ESPHome](https://github.com/immortalbob/303ESP32S3-AI-ESPHome).

## Versies

Zie [`VERSIES.md`](VERSIES.md). De versie zie je ook in Home Assistant (*Firmware*), op `/status` en onderaan in de app-instellingen.

## Licentie

[MIT](LICENSE)
