package de.versandapp.data.mail

import de.versandapp.data.ai.ClaudeApi
import de.versandapp.data.carrier.CarrierDetector
import de.versandapp.data.model.Carrier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extrahiert Sendungen per Claude aus Versand-Mails – findet im Gegensatz zum
 * Regex-Parser auch Trackingnummern in unstrukturierten Mails beliebiger
 * Shops (inkl. Auslandsbestellungen).
 *
 * Nutzt Claude Haiku (schnell und günstig – ein Scan von ~25 Mails kostet
 * unter einen Cent) mit strukturiertem JSON-Output.
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
                appendLine(mail.body.take(MAX_BODY_CHARS))
                appendLine()
            }
        }

        val response = api.createMessage(
            apiKey = apiKey,
            model = MODEL,
            maxTokens = 2048,
            system = SYSTEM_PROMPT,
            userText = mailDump,
            outputFormat = json.parseToJsonElement(OUTPUT_SCHEMA).jsonObject,
        )

        val text = api.textContent(response)
        val root = json.parseToJsonElement(text).jsonObject

        return root["shipments"]?.jsonArray.orEmpty()
            .mapNotNull { element ->
                val shipment = element.jsonObject
                val trackingNumber = shipment["tracking_number"]?.jsonPrimitive?.content
                    ?.let { CarrierDetector.normalize(it) }
                    ?.takeIf { it.length >= 8 }
                    ?: return@mapNotNull null
                val carrier = shipment["carrier"]?.jsonPrimitive?.content
                    ?.let { name -> Carrier.entries.firstOrNull { it.name == name } }
                    ?: Carrier.OTHER
                ShipmentSuggestion(
                    trackingNumber = trackingNumber,
                    carrier = carrier,
                    sourceSubject = shipment["label"]?.jsonPrimitive?.content ?: "",
                )
            }
            .distinctBy { it.trackingNumber }
    }

    companion object {
        private const val MODEL = "claude-haiku-4-5"
        private const val MAX_MAILS = 50
        private const val MAX_BODY_CHARS = 1500

        private val SYSTEM_PROMPT = """
            Du extrahierst Paket-Sendungen aus E-Mails. Du erhältst mehrere Mails,
            darunter Versandbestätigungen, Zustellbenachrichtigungen und auch
            irrelevante Mails (Newsletter, Werbung). Erfasse ALLE Sendungen, die
            mit einer Paketlieferung zu tun haben – lass keine Lieferung aus.

            Regeln:
            - Bevorzugt echte Trackingnummern von Paketdiensten verwenden; keine
              Rechnungs- oder Kundennummern.
            - WICHTIG, Sonderfall Amazon: Amazon-Versandmails enthalten oft keine
              Trackingnummer. Nutze dann die Amazon-Bestellnummer (Format
              123-1234567-1234567) als tracking_number und carrier AMAZON, damit
              die Lieferung trotzdem erfasst wird.
            - carrier ist der Dienstleister, der das Paket transportiert (nicht der
              Shop). Wenn unklar: OTHER.
            - label ist eine kurze Beschreibung für den Nutzer, z. B. Shop und
              Artikel ("Zalando – Schuhe", "Amazon – Bürstenaufsatz-Set").
            - Dieselbe Sendung nur einmal ausgeben (mehrere Mails zur selben
              Lieferung zusammenfassen).
            - Reine Werbe-/Newsletter-Mails ignorieren. Wenn gar keine Sendungen
              enthalten sind, gib eine leere Liste zurück.
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
                      "enum": ["DHL", "DEUTSCHE_POST", "HERMES", "DPD", "GLS", "UPS", "FEDEX", "AMAZON", "OTHER"]
                    },
                    "label": {"type": "string"}
                  },
                  "required": ["tracking_number", "carrier", "label"],
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
