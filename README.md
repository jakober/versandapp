# VersandApp 📦

Eine Android-App, die Sendungen **aller Versanddienstleister** an einem Ort
verfolgt – mit farbigem Carrier-Badge, aktuellem Status und komplettem
Sendungsverlauf pro Paket.

## Schritt 1 (dieses Projekt): Android-App

**Stack:** Kotlin · Jetpack Compose (Material 3) · Room · OkHttp · kotlinx.serialization

### Features

- 📋 Paketliste mit Carrier-Badge, Status-Chip und letztem Ereignis
- 🔍 **Automatische Carrier-Erkennung** anhand des Trackingnummer-Formats
  (DHL, Deutsche Post, Hermes, DPD, GLS, UPS, FedEx, Amazon Logistics)
- 🕐 Detailansicht mit Sendungsverlauf als Timeline
- 🔗 Absprung zur offiziellen Tracking-Seite des Anbieters
- 💾 Lokale Speicherung (Room) – funktioniert offline
- 🎨 Material You / Dynamic Color, heller und dunkler Modus

### Build

Projekt in **Android Studio** öffnen (Ladybug oder neuer) und auf einem Gerät/
Emulator mit Android 8.0+ starten. Alternativ per CLI (Android SDK nötig):

```bash
./gradlew assembleDebug
```

### Woher kommen die Trackingdaten?

Die App abstrahiert die Datenquelle hinter dem Interface
[`TrackingProvider`](app/src/main/java/de/versandapp/data/tracking/TrackingProvider.kt).
Aktuell registriert (siehe `VersandApp.kt`):

| Provider | Zweck |
| --- | --- |
| `DhlTrackingProvider` | Echte Daten über die **DHL Unified Tracking API** – kostenloser Key auf [developer.dhl.com](https://developer.dhl.com), in `VersandApp.DHL_API_KEY` eintragen |
| `DemoTrackingProvider` | Fallback mit realistischen Beispieldaten, damit die App **ohne Keys sofort läuft** |

**Wichtig zu wissen:** Es gibt keine kostenlose "eine API für alles".
Realistische Optionen für weitere Carrier:

1. **Direkte Carrier-APIs** (je ein Provider pro Dienst):
   - DHL / Deutsche Post: developer.dhl.com (kostenlos, Ratenlimit)
   - UPS: developer.ups.com (kostenlos, OAuth)
   - FedEx: developer.fedex.com (kostenlos)
   - DPD / GLS / Hermes: APIs primär für Geschäftskunden – für Privatnutzung
     bleibt sonst nur der Link zur Website (in der App bereits eingebaut)
2. **Aggregator-Dienste** – eine API, 1000+ Carrier, aber kostenpflichtig ab
   gewissem Volumen: [17track](https://api.17track.net),
   [Ship24](https://www.ship24.com), [AfterShip](https://www.aftership.com)
3. **Kein Scraping** der Carrier-Websites – rechtlich heikel (AGB) und bricht
   ständig.

Ein neuer Carrier ist damit: `TrackingProvider` implementieren + in
`VersandApp.onCreate()` registrieren. Fertig.

### Hinweis zu Logos

Die Original-Logos der Dienstleister sind **Markenzeichen** und dürfen nicht
einfach mitgeliefert werden. Die App zeigt daher Badges in den jeweiligen
Markenfarben mit Kürzel (DHL-Gelb, DPD-Rot, UPS-Braun/Gold …). Sobald
lizenzierte Logo-Assets vorliegen, können sie in `res/drawable` abgelegt und in
`CarrierBadge.kt` gerendert werden.

## Schritt 2 (geplant): Postfach-Anbindung

Ziel: E-Mail-Konto verknüpfen, Versandbestätigungen automatisch erkennen und
Pakete ohne manuelles Eintippen importieren.

Grober Plan:

1. **Gmail zuerst** – OAuth-Login mit der Gmail API und dem minimalen Scope
   `gmail.readonly`; Suche nach Mails von bekannten Absendern
   (`noreply@dhl.de`, `versandbestaetigung@amazon.de` …)
2. **Parser pro Absender**: Trackingnummern per Regex aus Betreff/Body ziehen –
   der vorhandene `CarrierDetector` validiert und ordnet sie direkt einem
   Carrier zu
3. Treffer als Vorschlagsliste anzeigen ("3 neue Sendungen gefunden –
   importieren?"), nicht stillschweigend importieren
4. Später IMAP für andere Anbieter (GMX, web.de, Posteo …)
5. Datenschutz: Verarbeitung vollständig auf dem Gerät, keine Mail-Inhalte an
   Server; Achtung: Googles Verifizierung für sensible Scopes einplanen, wenn
   die App in den Play Store soll

## Architektur

```
ui/            Compose-Screens (Liste, Detail, Hinzufügen-Dialog) + ViewModel
data/model/    Parcel, TrackingEvent, Carrier, ParcelStatus
data/db/       Room-Datenbank + DAO
data/carrier/  CarrierDetector (Format-Erkennung der Trackingnummern)
data/tracking/ TrackingProvider-Abstraktion, DHL-Anbindung, Demo-Provider,
               TrackingRepository (Fachlogik)
```

### Sinnvolle nächste Schritte

- [ ] Hintergrund-Aktualisierung per WorkManager + Push-Benachrichtigung bei
      Statuswechsel ("Dein Paket ist in Zustellung!")
- [ ] UPS-/FedEx-Provider (kostenlose Developer-Keys)
- [ ] API-Keys über `local.properties`/BuildConfig statt Konstante
- [ ] Barcode-Scanner zum Erfassen der Trackingnummer
- [ ] Archiv für zugestellte Pakete
- [ ] Schritt 2: Gmail-Import (siehe oben)
