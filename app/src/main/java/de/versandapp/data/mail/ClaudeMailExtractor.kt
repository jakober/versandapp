package de.versandapp.data.mail

import de.versandapp.data.ai.ClaudeApi
import de.versandapp.data.carrier.CarrierDetector
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extrahiert Sendungen per Claude aus Versand-Mails – findet im Gegensatz zum
 * Regex-Parser auch Trackingnummern in unstrukturierten Mails beliebiger
 * Shops (inkl. Auslandsbestellungen).
 *
 * Nutzt Claude Sonnet mit strukturiertem JSON-Output – das stärkere Modell
 * erkennt auch schwierige Mails zuverlässig; ein Scan kostet wenige Cent.
 */
class ClaudeMailExtractor(
    private val api: ClaudeApi = ClaudeApi(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extract(apiKey: String, mails: List<MailMessage>): List<ShipmentSuggestion> {
        if (mails.isEmpty()) return emptyList()

        val mailDump = buildString {
            mails.take(MAX_MAILS).forEachIndexed { index, mail ->
                appendLine("--- Mail ${index + 1} ---")
                appendLine("Von: ${mail.from}")
                appendLine("Betreff: ${mail.subject}")
                appendLine(trimBody(mail.body))
                // Hinweis: aus Tracking-Links extrahierte Kandidaten-Nummern, die
                // sonst (z. B. bei sehr langen Mails) übersehen werden könnten.
                val hints = ShipmentEmailParser.linkCandidateNumbers(mail)
                if (hints.isNotEmpty()) {
                    appendLine("Hinweis – aus Sendungs-Links extrahierte Kandidaten-Trackingnummern: " +
                        hints.joinToString(", "))
                }
                appendLine()
            }
        }

        val response = api.createMessage(
            apiKey = apiKey,
            model = MODEL,
            maxTokens = 4096,
            system = SYSTEM_PROMPT,
            userText = mailDump,
            outputFormat = json.parseToJsonElement(OUTPUT_SCHEMA).jsonObject,
        )

        val text = api.textContent(response)
        val root = json.parseToJsonElement(text).jsonObject

        return root["shipments"]?.jsonArray.orEmpty()
            .mapNotNull { element ->
                val shipment = element.jsonObject
                // Nur klar belegte Sendungen übernehmen: unsichere Treffer (z. B.
                // eine lange Nummer aus einem Newsletter) fliegen raus, damit die
                // Liste nicht mit Fehltreffern volläuft.
                val confidence = shipment["confidence"]?.jsonPrimitive?.content
                if (confidence != null && confidence != "high") return@mapNotNull null
                val trackingNumber = shipment["tracking_number"]?.jsonPrimitive?.content
                    ?.let { CarrierDetector.normalize(it) }
                    ?.takeIf { it.length >= 8 && !CarrierDetector.looksLikeTimestamp(it) }
                    ?: return@mapNotNull null
                val carrier = shipment["carrier"]?.jsonPrimitive?.content
                    ?.let { name -> Carrier.entries.firstOrNull { it.name == name } }
                    ?: Carrier.OTHER
                val status = shipment["status"]?.jsonPrimitive?.content
                    ?.let { name -> ParcelStatus.entries.firstOrNull { it.name == name } }
                ShipmentSuggestion(
                    trackingNumber = trackingNumber,
                    carrier = carrier,
                    sourceSubject = shipment["label"]?.jsonPrimitive?.content ?: "",
                    initialStatus = status,
                )
            }
            .distinctBy { it.trackingNumber }
    }

    /**
     * Kürzt den Mail-Text fürs Modell – aber so, dass die „Links:"-Sektion am
     * Ende (dort stehen die Trackingnummern aus den Buttons!) IMMER erhalten
     * bleibt. Vorher wurde stumpf bei MAX_BODY_CHARS abgeschnitten, wodurch bei
     * langen HTML-Mails (z. B. eBay) genau die Links wegfielen.
     */
    private fun trimBody(body: String): String {
        val marker = "\n\nLinks:\n"
        val markerIndex = body.indexOf(marker)
        if (markerIndex < 0) return body.take(MAX_BODY_CHARS)

        val visible = body.substring(0, markerIndex)
        val links = body.substring(markerIndex) // inkl. Marker + URLs
        val linksPart = links.take(MAX_LINKS_CHARS)
        val visibleBudget = (MAX_BODY_CHARS - linksPart.length).coerceAtLeast(1500)
        return visible.take(visibleBudget) + linksPart
    }

    companion object {
        private const val MODEL = "claude-sonnet-5"
        private const val MAX_MAILS = 60
        private const val MAX_BODY_CHARS = 6000
        private const val MAX_LINKS_CHARS = 2500

        private val SYSTEM_PROMPT = """
            Du extrahierst Paket-Sendungen aus E-Mails für eine Tracking-App.
            Du erhältst ALLE Mails eines Zeitfensters – die meisten haben nichts
            mit Versand zu tun (Newsletter, Rechnungen, Werbung, privat). Verlasse
            dich NICHT auf den Absender: Versandhinweise kommen von beliebigen
            Shops und Marktplätzen (Amazon, eBay, Kleinanzeigen, Etsy, kleine
            Onlineshops, AliExpress, Temu …), teils vom Verkäufer persönlich.

            LIES JEDE MAIL VOLLSTÄNDIG im Detail und entscheide: Geht es darum,
            dass ein Paket/eine Bestellung VERSCHICKT wurde bzw. UNTERWEGS ist
            oder ZUGESTELLT wird? Achte auf Formulierungen wie „versandt",
            „verschickt", „unterwegs", „auf dem Weg", „shipped", „on its way",
            „Sendung", „Zustellung", „in Zustellung". Wenn ja und eine
            Trackingnummer auffindbar ist: erfassen.

            Deine Aufgabe: JEDE echte Paket-Sendung mit ihrer korrekten
            TRACKINGNUMMER erfassen. Verlässlichkeit ist entscheidend – weder
            Sendungen auslassen noch falsche Nummern eintragen.

            SO FINDEST DU DIE RICHTIGE TRACKINGNUMMER (Priorität von oben nach unten):

            1. Ein ausdrücklich als Sendungsnummer bezeichneter Wert. Achte auf
               Bezeichnungen wie "Sendungsnummer", "Sendungsverfolgungsnummer",
               "Trackingnummer", "Paketnummer", "tracking number", "tracking id".
               Der direkt daneben stehende Wert ist die Trackingnummer.

            2. Die Nummer aus einem Sendungsverfolgungs-Link. Unter "Links:" am
               Ende der Mail stehen die URLs der Buttons/Links. Ziehe die
               Trackingnummer aus dem passenden Parameter, z. B.:
                 - DHL: ...?...idc=00340434787961604064   → 00340434787961604064
                 - DHL: ...piececode=XXXX oder ?nummer=XXXX
                 - Hermes/DPD/GLS/UPS/FedEx: die lange Nummer im Tracking-Link
               Nutze IMMER die Nummer aus dem offiziellen Tracking-Link, wenn
               vorhanden – sie ist zuverlässiger als Zahlen im Fließtext.

            3. Sonderfall Amazon (nur wenn KEINE Trackingnummer vorhanden ist):
               Amazon-Versandmails haben oft keine Trackingnummer. Nutze dann die
               Amazon-Bestellnummer (Format 123-1234567-1234567) als
               tracking_number mit carrier AMAZON.

            Falls unter der Mail ein „Hinweis – aus Sendungs-Links extrahierte
            Kandidaten-Trackingnummern: …" steht: Das sind zuverlässig aus den
            Tracking-Links gezogene Nummern. Nutze sie, wenn die Mail wirklich
            eine Sendung ankündigt/verfolgt – Carrier und Status bestimmst du
            weiterhin selbst aus dem Mailtext.

            NIEMALS als Trackingnummer verwenden: Bestellnummer, Auftragsnummer,
            Rechnungsnummer, Kundennummer, Artikelnummer/SKU (z. B.
            "71804-253-40-0"), Gutschein-/Aktionscodes. Diese stehen oft prominent
            im Text, sind aber KEINE Trackingnummern. Im Zweifel: die Nummer aus
            dem Tracking-Link nehmen.

            CARRIER: der Dienstleister, der das Paket transportiert – nicht der
            Shop. Leite ihn aus Absender, Text UND Nummernformat ab. Achtung: Eine
            Mail kann von einem Dienst kommen, aber ein Paket eines anderen
            ankündigen (z. B. Hermes-Mail über eine FedEx-Sendung). Richte dich
            nach der Trackingnummer: beginnt sie mit "H" + Ziffern → HERMES;
            1Z... → UPS; JJD... → DHL; LP.../YT.../...CN → CAINIAO/YANWEN/CHINA_POST.
            Erlaubte Werte: DHL, DEUTSCHE_POST, HERMES, DPD, GLS, UPS, FEDEX,
            AMAZON, CHINA_POST, CAINIAO, YANWEN, OTHER. Wenn unklar: OTHER.

            label: kurze Beschreibung für den Nutzer aus Shop und Artikel, z. B.
            "HUT.de – Westernhut", "Amazon – Bürstenaufsatz-Set".

            status aus der Mail ableiten: REGISTERED (angekündigt/bestellt),
            IN_TRANSIT (versandt/unterwegs/auf dem Weg), OUT_FOR_DELIVERY
            (in Zustellung/in Auslieferung), DELIVERED (zugestellt/geliefert),
            sonst UNKNOWN.
            WICHTIG zum Status: Achte auf die ZEITFORM. Zukunftsformen wie „wird
            voraussichtlich am … zugestellt", „wird geliefert", „kommt morgen"
            bedeuten NICHT DELIVERED – das ist höchstens IN_TRANSIT bzw.
            OUT_FOR_DELIVERY. „in Zustellung" = OUT_FOR_DELIVERY, NICHT DELIVERED.
            DELIVERED nur, wenn die Zustellung eindeutig BEREITS ERFOLGT ist
            (z. B. Betreff „Zugestellt:"/„Geliefert:", „wurde zugestellt").
            Nimm im Zweifel den Betreff als maßgebliche Statusquelle.

            confidence – WIE SICHER ist es eine echte Sendung mit echter
            Trackingnummer? Sei streng, lieber weglassen als falsch erfassen:
              - "high": Die Mail ist eindeutig eine Versand-/Zustellmail UND die
                Nummer ist ausdrücklich als Sendungs-/Trackingnummer beschriftet
                ODER stammt aus einem offiziellen Sendungsverfolgungs-Link.
                (Amazon-Sonderfall: nur bei einer echten Amazon-Versand-/
                Zustellmail mit Bestellnummer.)
              - "medium": Versandkontext erkennbar, aber die Nummer stammt nur aus
                dem Fließtext ohne klare Beschriftung/Link.
              - "low": unsicher – könnte auch Bestell-/Rechnungs-/Artikelnummer
                sein, oder die Mail ist gar keine Versandmail.
            Gib NUR Sendungen mit confidence "high" aus. Alles Unsichere
            (medium/low) NICHT aufnehmen.

            Mehrere Mails zur selben Sendung zu einem Eintrag zusammenfassen
            (aktuellsten Status verwenden). Reine Werbung, Bestellbestätigungen
            ohne Versand, Rechnungen und Newsletter ignorieren. Enthält keine
            Mail eine sicher belegte Sendung, gib eine leere Liste zurück.
        """.trimIndent()

        private val OUTPUT_SCHEMA = """
        {
          "type": "json_schema",
          "schema": {
            "type": "object",
            "properties": {
              "shipments": {
                "type": "array",
                "items": {
                  "type": "object",
                  "properties": {
                    "tracking_number": {"type": "string"},
                    "carrier": {
                      "type": "string",
                      "enum": ["DHL", "DEUTSCHE_POST", "HERMES", "DPD", "GLS", "UPS", "FEDEX", "AMAZON", "CHINA_POST", "CAINIAO", "YANWEN", "OTHER"]
                    },
                    "label": {"type": "string"},
                    "status": {
                      "type": "string",
                      "enum": ["REGISTERED", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "UNKNOWN"]
                    },
                    "confidence": {
                      "type": "string",
                      "enum": ["high", "medium", "low"]
                    }
                  },
                  "required": ["tracking_number", "carrier", "label", "status", "confidence"],
                  "additionalProperties": false
                }
              }
            },
            "required": ["shipments"],
            "additionalProperties": false
          }
        }
        """.trimIndent()
    }
}
