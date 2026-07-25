package de.versandapp.data.carrier

import de.versandapp.data.model.Carrier

/**
 * Erkennt anhand des Formats der Trackingnummer, welche Dienstleister in Frage
 * kommen. Viele Formate überschneiden sich (z. B. 14-stellige Nummern bei
 * Hermes und DPD), deshalb liefert [detect] eine nach Wahrscheinlichkeit
 * sortierte Liste von Kandidaten – die UI schlägt den ersten vor, der Nutzer
 * kann korrigieren.
 */
object CarrierDetector {

    private data class Rule(val carrier: Carrier, val confidence: Int, val regex: Regex)

    private val rules = listOf(
        // UPS: eindeutiges "1Z"-Präfix
        Rule(Carrier.UPS, 100, Regex("^1Z[0-9A-Z]{16}$")),
        // Amazon Logistics (Trackingnummer oder Bestellnummer)
        Rule(Carrier.AMAZON, 100, Regex("^TB[AC][0-9]{12,15}$")),
        Rule(Carrier.AMAZON, 95, Regex("^[0-9]{3}-[0-9]{7}-[0-9]{7}$")),
        // DHL Paket (DE): 20-stellig, meist mit 00340434 beginnend
        Rule(Carrier.DHL, 95, Regex("^00340434[0-9]{12}$")),
        Rule(Carrier.DHL, 70, Regex("^[0-9]{20}$")),
        // DHL-Sendungsnummer mit Leitcode-Präfix 340… (18–20-stellig, z. B. aus
        // dem piececode-Link, wo die führende 00 fehlt).
        Rule(Carrier.DHL, 50, Regex("^340[0-9]{15,17}$")),
        Rule(Carrier.DHL, 60, Regex("^JJD[0-9]{16,20}$")),
        Rule(Carrier.DHL, 40, Regex("^[0-9]{12}$")),
        // Deutsche Post / internationale S10-Nummern, z. B. RR123456789DE
        Rule(Carrier.DEUTSCHE_POST, 90, Regex("^[A-Z]{2}[0-9]{9}DE$")),
        // China-Sendungen: S10 mit CN-Endung, Cainiao (LP…), Yanwen (YT…)
        Rule(Carrier.CHINA_POST, 95, Regex("^[A-Z]{2}[0-9]{9}CN$")),
        Rule(Carrier.CAINIAO, 90, Regex("^LP[0-9]{14,16}$")),
        Rule(Carrier.YANWEN, 95, Regex("^YT[0-9]{16}$")),
        Rule(Carrier.DEUTSCHE_POST, 60, Regex("^[A-Z]{2}[0-9]{9}[A-Z]{2}$")),
        // Hermes: 14-stellig
        Rule(Carrier.HERMES, 55, Regex("^[0-9]{14}$")),
        Rule(Carrier.HERMES, 80, Regex("^H[0-9]{19,20}$")),
        // DPD: 14-stellig, beginnt häufig mit 0
        Rule(Carrier.DPD, 60, Regex("^0[0-9]{13}$")),
        Rule(Carrier.DPD, 50, Regex("^[0-9]{14}$")),
        // GLS: 11–12-stellig
        Rule(Carrier.GLS, 55, Regex("^[0-9]{11,12}$")),
        // FedEx: 12 oder 15 Ziffern
        Rule(Carrier.FEDEX, 45, Regex("^[0-9]{12}$")),
        Rule(Carrier.FEDEX, 55, Regex("^[0-9]{15}$")),
    )

    /** Nach Konfidenz sortierte Kandidaten; leere Liste, wenn nichts passt. */
    fun detect(rawTrackingNumber: String): List<Carrier> {
        val normalized = normalize(rawTrackingNumber)
        if (normalized.isEmpty()) return emptyList()
        return rules
            .filter { it.regex.matches(normalized) }
            .sortedByDescending { it.confidence }
            .map { it.carrier }
            .distinct()
    }

    fun normalize(rawTrackingNumber: String): String =
        rawTrackingNumber.replace(Regex("\\s"), "").uppercase()
}
