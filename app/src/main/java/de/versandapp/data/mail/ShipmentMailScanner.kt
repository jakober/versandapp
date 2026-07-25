package de.versandapp.data.mail

import de.versandapp.data.log.DiagnosticsLog

/**
 * Gemeinsame Scan-Logik für manuellen und automatischen Mail-Import:
 * Versand-Mails laden und Sendungen extrahieren – per Claude, wenn ein
 * Anthropic-Key vorhanden ist, sonst (oder bei Fehlern) per Regex-Parser.
 */
class ShipmentMailScanner(
    private val gmail: GmailService = GmailService(),
    private val claudeExtractor: ClaudeMailExtractor = ClaudeMailExtractor(),
) {

    /**
     * @param afterEpochSeconds Nur Mails ab diesem Zeitpunkt; 0 = letzte 24 h.
     */
    suspend fun scan(
        gmailAccessToken: String,
        anthropicApiKey: String,
        afterEpochSeconds: Long = 0L,
    ): List<ShipmentSuggestion> {
        val mails = gmail.searchShipmentMails(gmailAccessToken, afterEpochSeconds)
        // Gescannte Mails ins Protokoll aufnehmen, damit der Nutzer sie selbst
        // in Gmail öffnen kann (z. B. um einen Abmelde-Link anzuklicken).
        mails.forEach {
            DiagnosticsLog.addMail(it.from, it.subject, GmailService.gmailDeepLink(it))
        }

        // Zuverlässige, strukturierte Funde (Trackingnummern aus Links wie
        // piececode=/idc=, Amazon-Bestellnummern) IMMER mitnehmen – auf dem
        // ungekürzten Text und unabhängig von der KI. So rutschen z. B. DHL-
        // „unterwegs"-Mails, bei denen die Nummer nur tief im Button-Link steckt,
        // nicht mehr durch (die KI kürzt lange Mails und übersieht sie sonst).
        val reliable = ShipmentEmailParser.parseReliableAll(mails)

        val primary = if (anthropicApiKey.isNotBlank()) {
            runCatching { claudeExtractor.extract(anthropicApiKey, mails) }
                .getOrElse { ShipmentEmailParser.parseAll(mails) }
        } else {
            ShipmentEmailParser.parseAll(mails)
        }

        // KI-/Regex-Ergebnisse zuerst (bessere Labels), zuverlässige Funde
        // ergänzen fehlende Nummern. Duplikate über die Trackingnummer entfernen.
        return (primary + reliable).distinctBy { it.trackingNumber }
    }
}
