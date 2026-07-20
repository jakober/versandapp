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
                userText = buildUserText(trackingNumber, carrier),
                tools = json.parseToJsonElement(WEB_SEARCH_TOOLS).jsonArray,
            )
        } catch (e: ClaudeException) {
            throw TrackingException("Claude-Abfrage fehlgeschlagen: ${e.message}", e)
        }

        return parse(api.textContent(response))
    }

    /**
     * Suchmaschinen indexieren einzelne Trackingnummern nicht – deshalb
     * bekommt Claude die konkreten Tracking-URLs mitgeliefert und kann sie
     * direkt per Web-Abruf öffnen, statt nur zu suchen.
     */
    private fun buildUserText(trackingNumber: String, carrier: Carrier): String {
        // Bewusst keine 17track-Links: dort steht die Nummer im URL-Fragment
        // (nach #), das nie an den Server geht – ein Abruf liefert nur die
        // leere App-Hülle ohne Statusdaten.
        val urls = buildList {
            carrier.trackingUrl(trackingNumber)?.let { add(it) }
            // Direkte Daten-Endpunkte, die den Status als JSON liefern (statt der
            // JavaScript-Seite). Erhöht die Trefferquote deutlich, weil die
            // sichtbare Tracking-Seite die Daten meist erst per Nachladen zeigt.
            when (carrier) {
                Carrier.DHL, Carrier.DEUTSCHE_POST -> add(
                    "https://www.dhl.de/int-verfolgen/data/search" +
                        "?piececode=$trackingNumber&language=de&noRedirect=true"
                )
                Carrier.HERMES -> add(
                    "https://api.myhermes.de/hermes-tracking-progress-rest/" +
                        "consumer/v1/tracking/$trackingNumber"
                )
                Carrier.GLS -> add(
                    "https://gls-group.eu/app/service/open/rest/DE/de/rstt001/$trackingNumber"
                )
                Carrier.DPD -> add(
                    "https://tracking.dpd.de/rest/plc/de_DE/$trackingNumber"
                )
                else -> {}
            }
            // Tracking-Portale als zusätzliche Quelle (decken viele Carrier und
            // Auslandssendungen ab).
            add("https://parcelsapp.com/en/tracking/$trackingNumber")
        }
        return buildString {
            appendLine("Dienstleister: ${carrier.displayName}")
            appendLine("Trackingnummer: $trackingNumber")
            appendLine()
            appendLine("Diese URLs kannst du direkt abrufen (die /rest-, /data- und")
            appendLine("api.-URLs liefern den Status meist als JSON):")
            urls.forEach { appendLine(it) }
        }
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
        // Schnellstes/günstigstes Modell – fürs Auslesen von Tracking-Seiten
        // ausreichend; die Web-Tools nutzen die zu Haiku passenden Varianten.
        private const val MODEL = "claude-haiku-4-5"

        private val SYSTEM_PROMPT = """
            Du bist ein Sendungsverfolgungs-Assistent. Du erhältst einen
            Versanddienstleister und eine Trackingnummer. Recherchiere den
            aktuellen Sendungsstatus per Web-Suche (z. B. auf der Website des
            Dienstleisters oder über Tracking-Portale). Bei China-Sendungen
            (China Post, Cainiao/AliExpress, Yanwen – Nummern wie LP…, YT…
            oder …CN) sind global.cainiao.com und 17track.net gute Quellen;
            nach der Übergabe an einen deutschen Zusteller lohnt sich auch
            dessen Tracking-Seite.

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
            - Rufe zuerst die mitgelieferten URLs direkt ab (web_fetch). Die
              /rest-, /data- und api.-URLs liefern den Status oft direkt als
              JSON – das ist die zuverlässigste Quelle. Suchmaschinen indexieren
              einzelne Trackingnummern in der Regel nicht.
            - Wenn die sichtbare Tracking-Seite keinen Status zeigt (die Daten
              werden dort per JavaScript nachgeladen), nutze den JSON-Endpunkt
              oder suche die Nummer zusätzlich auf Tracking-Portalen
              (parcelsapp.com, ordertracker.com, 17track.net) und rufe deren
              Trefferseite ab.
            - Gib nicht zu früh auf: Probiere alle mitgelieferten URLs sowie eine
              ergänzende Web-Suche, bevor du {"found": false} antwortest.
            - events chronologisch aufsteigend, nur Ereignisse aus den abgerufenen
              Seiten bzw. Suchergebnissen.
            - Erfinde NIEMALS Statusdaten. Wenn du nach allen Versuchen keine
              verlässlichen Informationen zu genau dieser Trackingnummer findest,
              antworte mit {"found": false}.
            - Amazon-Bestellnummern (Format 123-1234567-1234567) sind nicht
              öffentlich einsehbar – antworte dann sofort mit {"found": false},
              ohne Web-Suche.
        """.trimIndent()

        private val WEB_SEARCH_TOOLS = """
        [
          {"type": "web_search_20250305", "name": "web_search", "max_uses": 6},
          {"type": "web_fetch_20250910", "name": "web_fetch", "max_uses": 8}
        ]
        """.trimIndent()
    }
}
