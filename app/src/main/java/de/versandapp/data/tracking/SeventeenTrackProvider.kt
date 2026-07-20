package de.versandapp.data.tracking

import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Haupt-Tracking-Quelle: die offizielle 17track-API (api.17track.net) –
 * deckt praktisch alle Dienstleister weltweit ab, inklusive China
 * (Cainiao, Yanwen, China Post). Kostenloser Key mit Monats-Kontingent,
 * in den App-Einstellungen hinterlegen.
 *
 * Ablauf pro Abfrage: Nummer einmalig bei 17track registrieren
 * (idempotent, zählt aufs Kontingent), danach Status + Verlauf abrufen.
 */
class SeventeenTrackProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult =
        withContext(Dispatchers.IO) {
            // Registrierung ist idempotent – "bereits registriert" ist kein Fehler
            runCatching { post("register", trackingNumber) }

            val root = post("gettrackinfo", trackingNumber)
            parse(root)
        }

    private fun parse(root: JsonObject): TrackingResult {
        val accepted = root["data"]?.jsonObject
            ?.get("accepted")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?: throw TrackingException(
                "17track kennt diese Sendung noch nicht – bitte später erneut prüfen"
            )
        val trackInfo = accepted["track_info"]?.jsonObject
            ?: throw TrackingException("Keine Trackingdaten von 17track erhalten")

        val statusName = trackInfo["latest_status"]?.jsonObject
            ?.get("status")?.jsonPrimitive?.content
        if (statusName == null || statusName == "NotFound") {
            throw TrackingException("Beim Dienstleister liegen noch keine Daten zu dieser Sendung vor")
        }

        val events = trackInfo["tracking"]?.jsonObject
            ?.get("providers")?.jsonArray.orEmpty()
            .flatMap { provider -> provider.jsonObject["events"]?.jsonArray.orEmpty() }
            .mapNotNull { element ->
                val event = element.jsonObject
                val description = event["description"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TrackingUpdate(
                    timestamp = parseTimestamp(
                        event["time_iso"]?.jsonPrimitive?.content
                            ?: event["time_utc"]?.jsonPrimitive?.content
                    ),
                    description = description,
                    location = event["location"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    status = ParcelStatus.UNKNOWN,
                )
            }
            .sortedBy { it.timestamp }

        return TrackingResult(status = mapStatus(statusName), events = events)
    }

    private fun post(path: String, trackingNumber: String): JsonObject {
        val body = buildJsonArray {
            add(buildJsonObject { put("number", trackingNumber) })
        }.toString()

        val request = Request.Builder()
            .url("https://api.17track.net/track/v2.2/$path")
            .header("17token", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    throw TrackingException(
                        "17track antwortete mit HTTP ${response.code}: ${text?.take(200)}"
                    )
                }
                text ?: throw TrackingException("Leere Antwort von 17track")
            }
        } catch (e: IOException) {
            throw TrackingException("Netzwerkfehler bei der 17track-Abfrage", e)
        }

        return json.parseToJsonElement(responseBody).jsonObject
    }

    private fun mapStatus(status: String): ParcelStatus = when (status) {
        "InfoReceived" -> ParcelStatus.REGISTERED
        "InTransit" -> ParcelStatus.IN_TRANSIT
        "OutForDelivery", "AvailableForPickup" -> ParcelStatus.OUT_FOR_DELIVERY
        "Delivered" -> ParcelStatus.DELIVERED
        "DeliveryFailure", "Exception", "Expired" -> ParcelStatus.FAILED
        else -> ParcelStatus.UNKNOWN
    }

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(value.replace(" ", "T"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return System.currentTimeMillis()
    }
}
