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
- 📧 **Gmail-Import** (Schritt 2): Postfach verknüpfen und gefundene
  Sendungen mit einem Tap importieren
- 🔔 **Hintergrund-Aktualisierung** mit Benachrichtigung bei Statuswechsel
  (stündliches Polling per WorkManager, kein Server nötig)
- 🤖 **Claude-Integration** (optional, Anthropic-API-Key in den
  Einstellungen): KI-Mail-Erkennung findet Sendungen in beliebigen
  Shop-Mails, und ein Web-Suche-Fallback trackt Dienste ohne API
  (Hermes, DPD, GLS, Auslandspakete)

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
| `DhlTrackingProvider` | **DHL/Deutsche Post über die offizielle API** (zuverlässigste Quelle) – kostenloser Key auf [developer.dhl.com](https://developer.dhl.com) („Shipment Tracking – Unified") |
| `TracktryProvider` | **Haupt-Quelle für alle übrigen Dienste weltweit** (inkl. China): Tracktry-API (Pay-as-you-go-Ableger von 17track), Abrechnung pro Sendung im Cent-Bereich, Key auf [tracktry.com](https://www.tracktry.com) |
| `ClaudeTrackingProvider` | **KI-Fallback** (nur aktiv, wenn kein Tracktry-Key hinterlegt ist): Claude Haiku ruft Tracking-Seiten ab. Automatisch 1× täglich abends, nur offene Pakete |
| `DemoTrackingProvider` | Fallback mit Beispieldaten, damit die App **ohne Keys sofort läuft** |

Alle Keys werden in den App-Einstellungen (Zahnrad) gespeichert
(SharedPreferences), nicht im Code.

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

## Schritt 2: Postfach-Anbindung (Gmail)

Über das Mail-Symbol in der Paketliste lässt sich das Gmail-Postfach
verknüpfen. Die App durchsucht dann Versand-Mails der letzten 60 Tage
(bekannte Absender wie DHL, DPD, Hermes, Amazon … sowie typische Betreffe)
und zeigt Funde als **Vorschlagsliste** – importiert wird nur, was der
Nutzer bestätigt. Scope nur `gmail.readonly`.

Die Extraktion läuft zweistufig: Mit hinterlegtem Anthropic-Key analysiert
**Claude Haiku** die Mails (`ClaudeMailExtractor`, strukturierter
JSON-Output) – das findet auch Trackingnummern in unstrukturierten Mails
beliebiger Shops und Auslandsbestellungen; ein Scan kostet unter einen
Cent. Ohne Key (oder wenn die Claude-Abfrage fehlschlägt) übernimmt der
lokale Regex-Parser, dann bleibt die Verarbeitung komplett auf dem Gerät.
Hinweis: Mit Claude werden Mail-Auszüge an die Anthropic-API übertragen.

### Einmalige Einrichtung (Google Cloud Console)

Damit der Gmail-Login funktioniert, braucht die App einen OAuth-Client:

1. Projekt auf [console.cloud.google.com](https://console.cloud.google.com)
   anlegen und die **Gmail API** aktivieren
2. OAuth-Zustimmungsbildschirm konfigurieren (Testnutzer: eigene
   Gmail-Adresse eintragen)
3. OAuth-Client-ID vom Typ **Android** anlegen mit Paketname `de.versandapp`
   und dem **SHA-1** des Debug-Keystores. Das Repo enthält einen festen
   Debug-Keystore (`app/debug.keystore`), damit der Fingerabdruck über alle
   CI-Builds stabil bleibt:
   `A9:16:27:25:83:49:18:39:9B:F0:84:8B:00:3C:53:02:A1:76:BB:99`
4. Fertig – kein API-Key im Code nötig, die Zuordnung läuft über
   Paketname + SHA-1

Für eine Play-Store-Veröffentlichung verlangt Google eine Verifizierung der
App, da `gmail.readonly` ein sensibler Scope ist. Für den Eigenbedarf
(Testnutzer) reicht das Setup oben.

Später denkbar: IMAP-Anbindung für andere Anbieter (GMX, web.de, Posteo …).

## Statusabfrage & Benachrichtigungen ohne Bezahl-APIs

**Kurzfassung:** "Nur aktueller Stand statt Verlauf" macht die Datenbeschaffung
leider nicht kostenlos – die Hürde ist der API-Zugang an sich, nicht die
Datentiefe. Kostenlos und offiziell gehen DHL, UPS und FedEx (jeweils mit
Verlauf). Für DPD/GLS/Hermes gibt es keine offiziellen Gratis-APIs für
Privatnutzer; deren Web-Endpoints anzuzapfen wäre technisch möglich, ist aber
AGB-Grauzone und bricht ständig – deshalb nicht eingebaut.

**Benachrichtigungen** gehen dagegen komplett ohne Server und ohne Kosten:
Die App pollt per WorkManager stündlich im Hintergrund (Android erlaubt
minimal 15 Minuten, Intervall in `VersandApp.scheduleBackgroundRefresh()`)
und zeigt eine lokale Benachrichtigung, sobald sich ein Paketstatus ändert –
fühlt sich für den Nutzer wie Push an. Echtes Instant-Push (FCM) bräuchte ein
Backend, das zentral pollt, oder die (kostenpflichtigen) Webhooks eines
Aggregators.

## Architektur

```
ui/            Compose-Screens (Liste, Detail, Hinzufügen, Mail-Import) + ViewModels
data/model/    Parcel, TrackingEvent, Carrier, ParcelStatus
data/db/       Room-Datenbank + DAO
data/carrier/  CarrierDetector (Format-Erkennung der Trackingnummern)
data/tracking/ TrackingProvider-Abstraktion, DHL-Anbindung, Demo-Provider,
               TrackingRepository (Fachlogik)
data/mail/     GmailService (Gmail REST API) + ShipmentEmailParser
worker/        RefreshWorker (stündliches Polling + Benachrichtigungen)
```

### Sinnvolle nächste Schritte

- [ ] Barcode-Scanner zum Erfassen der Trackingnummer
- [ ] Archiv für zugestellte Pakete
- [ ] IMAP-Import für Nicht-Gmail-Postfächer
- [ ] Automatischer periodischer Mail-Scan (aktuell manuell per Knopf)
- [ ] Backend-Proxy für den Anthropic-Key, falls die App verteilt werden soll
