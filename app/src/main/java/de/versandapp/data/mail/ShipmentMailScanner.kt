package de.versandapp.data.mail

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
        if (anthropicApiKey.isNotBlank()) {
            runCatching { return claudeExtractor.extract(anthropicApiKey, mails) }
        }
        return ShipmentEmailParser.parseAll(mails)
    }
}
