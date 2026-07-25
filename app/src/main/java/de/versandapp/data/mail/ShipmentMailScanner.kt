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

        if (anthropicApiKey.isBlank()) {
            // Ohne KI-Key: lokaler Regex-Parser (voller Umfang, weniger präzise).
            return ShipmentEmailParser.parseAll(mails).distinctBy { it.trackingNumber }
        }

        val aiResults = runCatching { claudeExtractor.extract(anthropicApiKey, mails) }
            .getOrElse { e ->
                // Bei KI-Fehler NICHT auf den permissiven Volltext-Parser (parseAll)
                // zurückfallen – der würde jede lange Zahl als Sendung einsammeln.
                // Nur die zuverlässigen Link-/Amazon-Funde behalten und den Grund
                // protokollieren.
                DiagnosticsLog.add(
                    "Postfach", "Claude",
                    "Mail-Analyse fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}",
                    ok = false,
                )
                return ShipmentEmailParser.parseReliableAll(mails).distinctBy { it.trackingNumber }
            }

        // KI ist die maßgebliche Quelle für Carrier UND Status (sie liest den Text
        // und hat die Kandidaten-Nummern als Hinweis bekommen). Sicherheitsnetz:
        // sicher belegte Link-/Amazon-Nummern, die die KI dennoch ausgelassen hat,
        // ergänzen – aber OHNE Keyword-Status (initialStatus=null); den echten
        // Status liefert dann das Online-Tracking bzw. eine spätere Mail.
        val aiNumbers = aiResults.map { it.trackingNumber }.toSet()
        val supplement = ShipmentEmailParser.parseReliableAll(mails)
            .filter { it.trackingNumber !in aiNumbers }
            .map { it.copy(initialStatus = null) }
        return (aiResults + supplement).distinctBy { it.trackingNumber }
    }
}
