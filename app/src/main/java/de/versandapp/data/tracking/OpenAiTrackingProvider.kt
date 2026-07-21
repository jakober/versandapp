package de.versandapp.data.tracking

import de.versandapp.data.ai.OpenAiApi
import de.versandapp.data.ai.OpenAiException
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
 * Zusätzliche Online-Suche des Sendungsstatus über OpenAI (ChatGPT) mit
 * gehostetem Web-Suche-Tool. Kommt in der Kette hinter den DHL-/Claude-Quellen
 * und erhöht die Trefferchance, wenn diese nichts gefunden haben. Gleiches
 * Abendfenster + Drosselung wie beim Claude-Provider, damit automatische
 * Abfragen sparsam bleiben; der Aktualisieren-Knopf fragt jederzeit ab.
 */
class OpenAiTrackingProvider(
    private val apiKey: String,
    private val model: String,
    private val api: OpenAiApi = OpenAiApi(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    override val progressLabel: String = "ChatGPT recherchiert online …"

    // 20 h Mindestabstand + Abendfenster = genau eine automatische Abfrage pro Tag
    override val minRefreshIntervalMs: Long = 20L * 60L * 60L * 1000L

    override fun isBackgroundRefreshAllowedNow(): Boolean =
        LocalTime.now().hour in 18..22

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult {
        val text = try {
            api.respond(
                apiKey = apiKey,
                model = model,
                instructions = SYSTEM_PROMPT,
                input = buildUserText(trackingNumber, carrier),
                webSearch = true,
            )
        } catch (e: OpenAiException) {
            throw TrackingException("OpenAI-Abfrage fehlgeschlagen: ${e.message}", e)
        }
        return parse(text)
    }

    private fun buildUserText(trackingNumber: String, carrier: Carrier): String {
        val urls = buildList {
            carrier.trackingUrl(trackingNumber)?.let { add(it) }
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
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw TrackingException("Keine auswertbare Antwort von OpenAI erhalten")
        }
        val root = try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            throw TrackingException("OpenAI-Antwort konnte nicht geparst werden", e)
        }

        val found = root["found"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (!found) {
            throw TrackingException("OpenAI hat keine verlässlichen Trackingdaten gefunden")
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
        private val SYSTEM_PROMPT = """
            Du bist ein Sendungsverfolgungs-Assistent. Du erhältst einen
            Versanddienstleister und eine Trackingnummer. Ermittle den aktuellen
            Sendungsstatus per Web-Suche.

            Vorgehen:
            - Rufe zuerst die mitgelieferten URLs direkt ab. Die /rest-, /data-
              und api.-URLs liefern den Status oft direkt als JSON – das ist die
              zuverlässigste Quelle.
            - Zeigt die sichtbare Tracking-Seite keinen Status (per JavaScript
              nachgeladen), nutze den JSON-Endpunkt oder suche die Nummer
              zusätzlich auf Tracking-Portalen (parcelsapp.com, ordertracker.com,
              17track.net) und lies deren Trefferseite.
            - Bei China-Sendungen (China Post, Cainiao/AliExpress, Yanwen –
              Nummern wie LP…, YT… oder …CN) sind global.cainiao.com und
              17track.net gute Quellen.
            - Gib nicht zu früh auf: probiere alle URLs und eine ergänzende
              Web-Suche, bevor du "found": false meldest.

            Antworte AUSSCHLIESSLICH mit einem JSON-Objekt in genau diesem Format,
            ohne weiteren Text davor oder danach. Alle Beschreibungstexte auf
            DEUTSCH:
            {
              "found": true,
              "status": "REGISTERED|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|FAILED|UNKNOWN",
              "events": [
                {"timestamp": "ISO-8601 oder leer", "description": "…", "location": "… oder leer"}
              ]
            }

            Regeln:
            - events chronologisch aufsteigend, nur Ereignisse aus den abgerufenen
              Seiten bzw. Suchergebnissen.
            - Erfinde NIEMALS Statusdaten. Findest du nach allen Versuchen keine
              verlässlichen Informationen zu genau dieser Trackingnummer, antworte
              mit {"found": false}.
            - Amazon-Bestellnummern (Format 123-1234567-1234567) sind nicht
              öffentlich einsehbar – antworte dann sofort mit {"found": false}.
        """.trimIndent()
    }
}
