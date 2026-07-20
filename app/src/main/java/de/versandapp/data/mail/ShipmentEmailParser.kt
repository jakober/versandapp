package de.versandapp.data.mail

import de.versandapp.data.carrier.CarrierDetector
import de.versandapp.data.model.Carrier

data class ShipmentSuggestion(
    val trackingNumber: String,
    val carrier: Carrier,
    val sourceSubject: String,
)

/**
 * Zieht Trackingnummern-Kandidaten per Regex aus Betreff und Body und
 * validiert sie über den [CarrierDetector]. Der Absender der Mail dient als
 * zusätzliches Signal für die Carrier-Zuordnung.
 *
 * Falsch-Positive (z. B. lange Bestellnummern) sind möglich – deshalb werden
 * Funde in der UI immer als Vorschlagsliste bestätigt, nie still importiert.
 */
object ShipmentEmailParser {

    private val senderToCarrier = listOf(
        "dhl" to Carrier.DHL,
        "deutschepost" to Carrier.DEUTSCHE_POST,
        "hermes" to Carrier.HERMES,
        "dpd" to Carrier.DPD,
        "gls" to Carrier.GLS,
        "ups" to Carrier.UPS,
        "fedex" to Carrier.FEDEX,
        "amazon" to Carrier.AMAZON,
    )

    private val candidateRegex = Regex(
        "\\b(" +
            "1Z[0-9A-Z]{16}" +          // UPS
            "|TB[AC][0-9]{12,15}" +     // Amazon Logistics
            "|JJD[0-9]{16,20}" +        // DHL Express
            "|H[0-9]{19,20}" +          // Hermes
            "|[A-Z]{2}[0-9]{9}[A-Z]{2}" + // S10 international
            "|[0-9]{11,20}" +           // numerische Formate (DHL, DPD, GLS, …)
            ")\\b"
    )

    /** Amazon-Bestellnummer – dient bei Amazon-Versandmails ohne Trackingnummer als Kennung. */
    private val amazonOrderRegex = Regex("\\b[0-9]{3}-[0-9]{7}-[0-9]{7}\\b")

    fun parse(mail: MailMessage): List<ShipmentSuggestion> {
        val text = (mail.subject + "\n" + mail.body).uppercase()
        val senderCarrier = senderToCarrier
            .firstOrNull { (key, _) -> mail.from.contains(key, ignoreCase = true) }
            ?.second

        val amazonOrders = if (senderCarrier == Carrier.AMAZON) {
            amazonOrderRegex.findAll(text).map { match ->
                ShipmentSuggestion(
                    trackingNumber = match.value,
                    carrier = Carrier.AMAZON,
                    sourceSubject = mail.subject,
                )
            }.toList()
        } else {
            emptyList()
        }

        return amazonOrders + candidateRegex.findAll(text)
            .map { it.value }
            .distinct()
            .mapNotNull { candidate ->
                val detected = CarrierDetector.detect(candidate)
                if (detected.isEmpty()) return@mapNotNull null
                val carrier = if (senderCarrier != null && senderCarrier in detected) {
                    senderCarrier
                } else {
                    detected.first()
                }
                ShipmentSuggestion(
                    trackingNumber = candidate,
                    carrier = carrier,
                    sourceSubject = mail.subject,
                )
            }
            .toList()
    }

    /** Parst mehrere Mails und entfernt Duplikate über alle Mails hinweg. */
    fun parseAll(mails: List<MailMessage>): List<ShipmentSuggestion> =
        mails.flatMap { parse(it) }.distinctBy { it.trackingNumber }
}
