package de.versandapp.data.tracking

import de.versandapp.data.ai.ClaudeApi
import de.versandapp.data.ai.ClaudeException
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fallback-Provider für Dienstleister ohne (kostenlose) API: Claude recherchiert
 * den Sendungsstatus per Web-Suche und liefert ihn strukturiert zurück.
 *
 * Wichtige Eigenschaften:
 * - Wird nur für Pakete genutzt, für die kein direkter Carrier-Provider
 *   registriert ist (Reihenfolge in VersandApp).
 * - Automatisch läuft die Abfrage nur **einmal täglich im Abendfenster**
 *   (ab 18 Uhr, via [isBackgroundRefreshAllowedNow] + [minRefreshIntervalMs]),
 *   und nur für noch nicht zugestellte Pakete – so bleiben die Kosten der
 *   Web-Suche klein. Der Aktualisieren-Button in der App fragt jederzeit ab.
 * - Claude wird angewiesen, nichts zu erfinden; wenn keine verlässlichen Daten
 *   gefunden werden, schlägt die Abfrage fehl und der letzte bekannte Status
 *   bleibt stehen.
 */
class ClaudeTrackingProvider(
    private val apiKey: String,
    private val api: ClaudeApi = ClaudeApi(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    // 20 h Mindestabstand + Abendfenster = genau eine automatische Abfrage pro Tag
    override val minRefreshIntervalMs: Long = 20L * 60L * 60L * 1000L

    override fun isBackgroundRefreshAllowedNow(): Boolean =
        LocalTime.now().hour in 18..22

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult {
        val response = try {
            api.createMessage(
                apiKey = apiKey,
                model = MODEL,
                maxTokens = 4096,
                system = SYSTEM_PROMPT,
                userText = "Dienstleister: ${carrier.displayName}\nTrackingnummer: $trackingNumber",
                tools = json.parseToJsonElement(WEB_SEARCH_TOOLS).jsonArray,
            )
        } catch (e: ClaudeException) {
            throw TrackingException("Claude-Abfrage fehlgeschlagen: ${e.message}", e)
        }

        return parse(api.textContent(response))
    }

    private fun parse(text: String): TrackingResult {
        // Web-Search-Antworten enthalten neben dem JSON auch Zitat-Markierungen –
        // deshalb das JSON-Objekt tolerant aus dem Text herausschneiden.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw TrackingException("Keine auswertbare Antwort von Claude erhalten")
        }
        val root = try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            throw TrackingException("Claude-Antwort konnte nicht geparst werden", e)
        }

        val found = root["found"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (!found) {
            throw TrackingException("Claude hat keine verlässlichen Trackingdaten gefunden")
        }

        val status = root["status"]?.jsonPrimitive?.content
            ?.let { name -> ParcelStatus.entries.firstOrNull { it.name == name } }
            ?: ParcelStatus.UNKNOWN

        val events = root["events"]?.jsonArray.orEmpty().mapNotNull { element ->
            val event = element.jsonObject
            val description = event["description"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TrackingUpdate(
                timestamp = parseTimestamp(event["timestamp"]?.jsonPrimitive?.content),
                description = description,
                location = event["location"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                status = ParcelStatus.UNKNOWN,
            )
        }.sortedBy { it.timestamp }

        return TrackingResult(status = status, events = events)
    }

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return System.currentTimeMillis()
    }

    companion object {
        private const val MODEL = "claude-opus-4-8"

        private val SYSTEM_PROMPT = """
            Du bist ein Sendungsverfolgungs-Assistent. Du erhältst einen
            Versanddienstleister und eine Trackingnummer. Recherchiere den
            aktuellen Sendungsstatus per Web-Suche (z. B. auf der Website des
            Dienstleisters oder über Tracking-Portale).

            Antworte AUSSCHLIESSLICH mit einem JSON-Objekt in genau diesem Format,
            ohne weiteren Text davor oder danach:
            {
              "found": true,
              "status": "REGISTERED|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|FAILED|UNKNOWN",
              "events": [
                {"timestamp": "ISO-8601 oder leer", "description": "…", "location": "… oder leer"}
              ]
            }

            Regeln:
            - events chronologisch aufsteigend, nur Ereignisse aus den Suchergebnissen.
            - Erfinde NIEMALS Statusdaten. Wenn du keine verlässlichen Informationen
              zu genau dieser Trackingnummer findest, antworte mit {"found": false}.
        """.trimIndent()

        private val WEB_SEARCH_TOOLS = """
        [
          {"type": "web_search_20260209", "name": "web_search", "max_uses": 4},
          {"type": "web_fetch_20260209", "name": "web_fetch", "max_uses": 4}
        ]
        """.trimIndent()
    }
}
