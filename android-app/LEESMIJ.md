# Babyfoon-app voor Android

Eén app voor het geluid van de babyfoon en de twee Reolink-camera's. Werkt **alleen thuis op je eigen wifi**.

## Wat de app doet

- **Luisteren**: live geluid van het babyfoon-bordje. Blijft werken met het scherm uit.
- **Volumemeter**: je ziet of er geluid is, ook als het geluid uit staat.
- **Melding bij geluid**: als het geluid boven de drempel komt.
- **Alarm als de verbinding wegvalt**: na 10 seconden zonder geluidsdata klinkt het alarm via het **alarmvolume**, ook als je telefoon op stil staat. Druk op "Gezien" om het stil te zetten. Na 5 minuten gaat het opnieuw af als de verbinding dan nog steeds weg is.
- **Terugpraten**: houd de knop ingedrukt en praat. Je stem komt zacht uit de speaker in de babykamer. Tijdens het praten staat meeluisteren even stil, anders zingt het rond. De eerste keer vraagt de app om toestemming voor de microfoon.
- **Camera's**: twee tegels met een stilstaand beeld dat elke 3 seconden ververst. Tik erop voor live beeld. Bij de E1 Zoom kun je draaien en zoomen door de knoppen **ingedrukt te houden**.

## Installeren (eenmalig, ongeveer 20 minuten)

1. **Installeer Android Studio** op je pc: <https://developer.android.com/studio>
2. **Open het project.** Kies *Open* en selecteer de map `android-app`. Wacht tot onderin "Gradle sync" klaar is. De eerste keer downloadt hij veel, dat kan een paar minuten duren.
3. **Zet ontwikkelaarsopties aan op je telefoon.** Ga naar *Instellingen > Over de telefoon* en tik 7 keer op *Buildnummer*. Zet daarna in *Ontwikkelaarsopties* de optie **USB-foutopsporing** aan.
4. **Sluit je telefoon aan** met een USB-kabel en sta de verbinding toe op je telefoon.
5. **Klik op ▶ (Run)** bovenin Android Studio. De app wordt geïnstalleerd en opent vanzelf.

Vraagt Android Studio om iets bij te werken, zoals de Gradle-plugin of de Kotlin-versie? Dat is prima, kies *Update*.

Krijg je een foutmelding bij het bouwen? Kopieer de rode tekst en stuur die naar Claude.

## Instellen in de app

1. **Tandwiel rechtsboven.**
2. **Babyfoon:** vul het IP-adres van het bordje in en druk op *Test babyfoon*.
   - Geef het bordje in je router een **vast IP-adres** (DHCP-reservering). Dan blijft het adres altijd gelijk.
3. **Camera's:** naam, IP-adres, gebruikersnaam en wachtwoord. Druk op *Test camera*.
   - Maak in de Reolink-app een **aparte gebruiker met alleen kijkrechten**.
   - In de camera moeten **HTTP** en **RTSP** aan staan: *Reolink-app > Instellingen > Netwerk > Geavanceerd > Poortinstellingen*. Werkt de camera al in Home Assistant, dan staan ze waarschijnlijk al aan.
4. Druk op **Batterijbesparing uitzetten**. Dit is belangrijk, anders kan Android de app 's nachts stoppen.
5. **Opslaan.**

## Eerste keer testen (doe dit echt)

- **Start de babyfoon** in de app en praat bij het bordje. De meter moet bewegen.
- **Trek de stekker uit het bordje.** Binnen ongeveer 10 seconden moet het alarm afgaan.
- **Zet je telefoon op stil** en doe de stekkertest nog een keer. Het alarm moet dan nog steeds klinken.
- **Drempel instellen:** kijk naar de meter als het stil is in de kamer en zet de drempel ongeveer 10 dB hoger.

Werkt een van deze tests niet, gebruik de app dan nog niet als enige babyfoon.

## Goed om te weten

- Bij sommige telefoons (Samsung, Xiaomi, OnePlus) moet je de app daarnaast in de batterij-instellingen op **"Onbeperkt"** zetten.
- **Twee telefoons** kunnen tegelijk luisteren. Een derde krijgt "Te veel luisteraars".
- Het geluid van de **camera** staat standaard uit, zodat je de babyfoon blijft horen.
- De wachtwoorden worden **versleuteld** opgeslagen op je telefoon.
