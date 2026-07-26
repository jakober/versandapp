package de.versandapp.data.mail

import android.content.Context
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import de.versandapp.data.carrier.CarrierDetector
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Postfach-Analyse mit **Gemini Nano – on-device** (AICore). Kein API-Key, keine
 * Cloud: das Modell läuft komplett auf dem Gerät und liest jede Mail einzeln
 * (kleiner Kontext). Verfügbar nur auf unterstützten Geräten (Android 12+ mit
 * AICore) – sonst wird diese Klasse gar nicht erst erzeugt (siehe
 * [createNanoExtractor]); und schlägt eine Analyse fehl, greift im
 * [ShipmentMailScanner] der lokale Parser als Notnetz.
 */
class NanoMailExtractor(private val context: Context) : MailExtractor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun extract(mails: List<MailMessage>): List<ShipmentSuggestion> {
        if (mails.isEmpty()) return emptyList()

        val model = GenerativeModel(
            generationConfig = generationConfig {
                context = this@NanoMailExtractor.context.applicationContext
                temperature = 0.1f
                topK = 16
                maxOutputTokens = 256
            }
        )
        try {
            // Löst ggf. den einmaligen Modell-Download aus; wirft, wenn Nano auf
            // diesem Gerät (noch) nicht bereit ist → Fallback im Scanner.
            model.prepareInferenceEngine()

            val out = mutableListOf<ShipmentSuggestion>()
            mails.take(MAX_MAILS).forEach { mail ->
                val text = runCatching { model.generateContent(buildPrompt(mail)).text }
                    .getOrNull()
                    ?: return@forEach
                out += parse(text, mail)
            }
            return out.distinctBy { it.trackingNumber }
        } finally {
            runCatching { model.close() }
        }
    }

    private fun buildPrompt(mail: MailMessage): String = buildString {
        appendLine("Du extrahierst Paketsendungen aus einer E-Mail für eine Tracking-App.")
        appendLine("Antworte AUSSCHLIESSLICH mit JSON in genau diesem Format:")
        appendLine("""{"shipments":[{"tracking_number":"...","carrier":"DHL","status":"IN_TRANSIT"}]}""")
        appendLine("carrier: DHL, DEUTSCHE_POST, HERMES, DPD, GLS, UPS, FEDEX, AMAZON oder OTHER.")
        appendLine("status: REGISTERED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED oder UNKNOWN.")
        appendLine("Zukunftsform wie 'wird zugestellt' ist NICHT DELIVERED. 'in Zustellung' = OUT_FOR_DELIVERY.")
        appendLine("Keine Bestell-/Rechnungsnummern als tracking_number. Keine Sendung? {\"shipments\":[]}.")
        appendLine("---")
        appendLine("Von: ${mail.from}")
        appendLine("Betreff: ${mail.subject}")
        appendLine(mail.body.take(MAX_BODY_CHARS))
    }

    private fun parse(text: String, mail: MailMessage): List<ShipmentSuggestion> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return emptyList()

        return root["shipments"]?.jsonArray.orEmpty().mapNotNull { element ->
            val shipment = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val number = shipment["tracking_number"]?.jsonPrimitive?.content
                ?.let { CarrierDetector.normalize(it) }
                ?.takeIf { it.length >= 8 && !CarrierDetector.looksLikeTimestamp(it) }
                ?: return@mapNotNull null
            val carrier = shipment["carrier"]?.jsonPrimitive?.content
                ?.let { name -> Carrier.entries.firstOrNull { it.name == name } }
                ?: CarrierDetector.detect(number).firstOrNull()
                ?: Carrier.OTHER
            val status = shipment["status"]?.jsonPrimitive?.content
                ?.let { name -> ParcelStatus.entries.firstOrNull { it.name == name } }
            ShipmentSuggestion(
                trackingNumber = number,
                carrier = carrier,
                sourceSubject = mail.subject,
                initialStatus = status,
            )
        }
    }

    companion object {
        private const val MAX_MAILS = 20
        private const val MAX_BODY_CHARS = 4000
    }
}
