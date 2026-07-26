package de.versandapp.data.mail

import de.versandapp.data.log.DiagnosticsLog

/** Analysiert Mails und liefert Sendungsvorschläge. Implementierung z. B. Gemini Nano (on-device). */
interface MailExtractor {
    suspend fun extract(mails: List<MailMessage>): List<ShipmentSuggestion>
}

/**
 * Gemeinsame Scan-Logik für manuellen und automatischen Postfach-Import:
 * Versand-Mails laden und Sendungen extrahieren.
 *
 * Reihenfolge: Wenn ein [extractor] (z. B. Gemini Nano, on-device) vorhanden ist,
 * bestimmt dieser Carrier + Status durch Lesen der Mails. Als Notnetz (kein
 * Extractor verfügbar / Analyse schlägt fehl) übernimmt der lokale Regex-Parser.
 * Es werden KEINE API-Keys und kein Online-Dienst mehr benötigt.
 */
class ShipmentMailScanner(
    private val gmail: GmailService = GmailService(),
    private val extractor: MailExtractor? = null,
) {

    /**
     * @param afterEpochSeconds Nur Mails ab diesem Zeitpunkt; 0 = letzte 48 h.
     */
    suspend fun scan(
        gmailAccessToken: String,
        afterEpochSeconds: Long = 0L,
    ): List<ShipmentSuggestion> {
        val mails = gmail.searchShipmentMails(gmailAccessToken, afterEpochSeconds)
        // Gescannte Mails ins Protokoll aufnehmen, damit der Nutzer sie selbst
        // in Gmail öffnen kann (z. B. um einen Abmelde-Link anzuklicken).
        mails.forEach {
            DiagnosticsLog.addMail(it.from, it.subject, GmailService.gmailDeepLink(it))
        }

        // Ohne on-device-Extractor: lokaler Regex-Parser (voller Umfang).
        val extractor = extractor
            ?: return ShipmentEmailParser.parseAll(mails).distinctBy { it.trackingNumber }

        val aiResults = runCatching { extractor.extract(mails) }
            .getOrElse { e ->
                // Analyse fehlgeschlagen: NICHT den permissiven Volltext-Parser nutzen
                // (der würde jede lange Zahl als Sendung nehmen). Nur die zuverlässigen
                // Link-/Amazon-Funde behalten und den Grund protokollieren.
                DiagnosticsLog.add(
                    "Postfach", "Nano",
                    "Analyse fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}",
                    ok = false,
                )
                return ShipmentEmailParser.parseReliableAll(mails).distinctBy { it.trackingNumber }
            }

        // Der Extractor liest den Text und bestimmt Carrier UND Status. Sicherheitsnetz:
        // sicher belegte Link-/Amazon-Nummern, die er dennoch ausgelassen hat, ergänzen –
        // aber OHNE abgeleiteten Status (den bestimmt dann eine spätere Mail).
        val aiNumbers = aiResults.map { it.trackingNumber }.toSet()
        val supplement = ShipmentEmailParser.parseReliableAll(mails)
            .filter { it.trackingNumber !in aiNumbers }
            .map { it.copy(initialStatus = null) }
        return (aiResults + supplement).distinctBy { it.trackingNumber }
    }
}
