package de.versandapp.data.mail

import de.versandapp.data.carrier.CarrierDetector
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus

data class ShipmentSuggestion(
    val trackingNumber: String,
    val carrier: Carrier,
    val sourceSubject: String,
    /**
     * Aus der Mail abgeleiteter Status – v. a. für Amazon-Sendungen, deren
     * Bestellnummer nicht online abrufbar ist, deren Zustellstatus aber in
     * der Mail steht ("in Zustellung", "zugestellt" …). null = unbekannt.
     */
    val initialStatus: ParcelStatus? = null,
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
        "aliexpress" to Carrier.CAINIAO,
        "cainiao" to Carrier.CAINIAO,
        "yanwen" to Carrier.YANWEN,
    )

    private val candidateRegex = Regex(
        "\\b(" +
            "1Z[0-9A-Z]{16}" +          // UPS
            "|TB[AC][0-9]{12,15}" +     // Amazon Logistics
            "|JJD[0-9]{16,20}" +        // DHL Express
            "|H[0-9]{19,20}" +          // Hermes
            "|LP[0-9]{14,16}" +         // Cainiao/AliExpress
            "|YT[0-9]{16}" +            // Yanwen
            "|[A-Z]{2}[0-9]{9}[A-Z]{2}" + // S10 international (auch …CN)
            "|[0-9]{11,20}" +           // numerische Formate (DHL, DPD, GLS, …)
            ")\\b"
    )

    /** Amazon-Bestellnummer – dient bei Amazon-Versandmails ohne Trackingnummer als Kennung. */
    private val amazonOrderRegex = Regex("\\b[0-9]{3}-[0-9]{7}-[0-9]{7}\\b")

    /** Trackingnummer aus DHL-/Carrier-Tracking-Links (idc=, piececode=, nummer=, tracknum=). */
    private val trackingLinkRegex = Regex(
        "(?i)(?:idc|piececode|piece|nummer|tracknum|trackingnumber|tracking_number|code)=([0-9A-Z]{8,30})"
    )

    /**
     * Leitet den Status aus Betreff/Text ab (Text bereits GROSSGESCHRIEBEN) –
     * v. a. für Amazon-Sendungen, die online nicht abrufbar sind. Reihenfolge
     * = Priorität, damit "zugestellt" nicht von "in Zustellung" überschrieben wird.
     */
    private fun statusFromText(text: String): ParcelStatus? = when {
        listOf("ZUGESTELLT", "GELIEFERT", "AUSGELIEFERT", "DELIVERED", "WURDE GELIEFERT")
            .any { text.contains(it) } -> ParcelStatus.DELIVERED
        listOf("IN ZUSTELLUNG", "IN AUSLIEFERUNG", "WIRD HEUTE ZUGESTELLT", "OUT FOR DELIVERY")
            .any { text.contains(it) } -> ParcelStatus.OUT_FOR_DELIVERY
        listOf("VERSANDT", "VERSCHICKT", "UNTERWEGS", "SHIPPED", "IN TRANSIT")
            .any { text.contains(it) } -> ParcelStatus.IN_TRANSIT
        else -> null
    }

    fun parse(mail: MailMessage): List<ShipmentSuggestion> {
        val text = (mail.subject + "\n" + mail.body).uppercase()
        val senderCarrier = senderToCarrier
            .firstOrNull { (key, _) -> mail.from.contains(key, ignoreCase = true) }
            ?.second
        val mailStatus = statusFromText(text)

        fun suggestionFor(candidate: String): ShipmentSuggestion? {
            val detected = CarrierDetector.detect(candidate)
            if (detected.isEmpty()) return null
            val carrier = if (senderCarrier != null && senderCarrier in detected) {
                senderCarrier
            } else {
                detected.first()
            }
            return ShipmentSuggestion(
                trackingNumber = candidate,
                carrier = carrier,
                sourceSubject = mail.subject,
                initialStatus = mailStatus,
            )
        }

        // Höchste Priorität: Trackingnummern aus Tracking-Links (idc=, piececode=, …)
        val linkNumbers = trackingLinkRegex.findAll(text)
            .map { CarrierDetector.normalize(it.groupValues[1]) }
            .distinct().toList()
        val linkSuggestions = linkNumbers.mapNotNull { suggestionFor(it) }

        val amazonOrders = if (senderCarrier == Carrier.AMAZON) {
            amazonOrderRegex.findAll(text).map { match ->
                ShipmentSuggestion(
                    trackingNumber = match.value,
                    carrier = Carrier.AMAZON,
                    sourceSubject = mail.subject,
                    initialStatus = mailStatus,
                )
            }.toList()
        } else {
            emptyList()
        }

        // Weitere Kandidaten aus dem Text; rein numerische nur, wenn kein
        // Link-Treffer da war (sonst würden Bestell-/Kundennummern eingesammelt).
        val textSuggestions = candidateRegex.findAll(text)
            .map { it.value }
            .distinct()
            .filter { it !in linkNumbers }
            .filter { candidate -> candidate.first().isLetter() || linkSuggestions.isEmpty() }
            .mapNotNull { suggestionFor(it) }
            .toList()

        return (linkSuggestions + amazonOrders + textSuggestions)
            .distinctBy { it.trackingNumber }
    }

    /** Parst mehrere Mails und entfernt Duplikate über alle Mails hinweg. */
    fun parseAll(mails: List<MailMessage>): List<ShipmentSuggestion> =
        mails.flatMap { parse(it) }.distinctBy { it.trackingNumber }
}
