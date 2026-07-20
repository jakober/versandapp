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
 * Haupt-Tracking-Quelle: die Ship24-API (api.ship24.com) – deckt alle
 * Dienstleister weltweit ab, inklusive China. Die API ist schon im
 * Gratis-Plan enthalten (10 Sendungen/Monat, kleinster Bezahlplan 3,90 $).
 *
 * `POST /trackers/track` ist idempotent: Der erste Aufruf legt den Tracker
 * an (zählt aufs Kontingent), jeder weitere liefert nur die aktuellen
 * Ereignisse.
 */
class Ship24Provider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("trackingNumber", trackingNumber) }.toString()

            val request = Request.Builder()
                .url("https://api.ship24.com/public/v1/trackers/track")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val responseBody = try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string()
                    if (!response.isSuccessful) {
                        throw TrackingException(
                            "Ship24 antwortete mit HTTP ${response.code}: ${text?.take(200)}"
                        )
                    }
                    text ?: throw TrackingException("Leere Antwort von Ship24")
                }
            } catch (e: IOException) {
                throw TrackingException("Netzwerkfehler bei der Ship24-Abfrage", e)
            }

            parse(json.parseToJsonElement(responseBody).jsonObject)
        }

    private fun parse(root: JsonObject): TrackingResult {
        val tracking = root["data"]?.jsonObject
            ?.get("trackings")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?: throw TrackingException("Keine Trackingdaten von Ship24 erhalten")

        val milestone = tracking["shipment"]?.jsonObject
            ?.get("statusMilestone")?.jsonPrimitive?.content

        val events = tracking["events"]?.jsonArray.orEmpty().mapNotNull { element ->
            val event = element.jsonObject
            val description = event["status"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TrackingUpdate(
                timestamp = parseTimestamp(event["occurrenceDatetime"]?.jsonPrimitive?.content),
                description = description,
                location = event["location"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                status = mapMilestone(event["statusMilestone"]?.jsonPrimitive?.content),
            )
        }.sortedBy { it.timestamp }

        if (events.isEmpty() && (milestone == null || milestone == "pending")) {
            throw TrackingException(
                "Beim Dienstleister liegen noch keine Daten zu dieser Sendung vor – " +
                    "bitte später erneut prüfen"
            )
        }

        return TrackingResult(status = mapMilestone(milestone), events = events)
    }

    private fun mapMilestone(milestone: String?): ParcelStatus = when (milestone) {
        "info_received" -> ParcelStatus.REGISTERED
        "in_transit" -> ParcelStatus.IN_TRANSIT
        "out_for_delivery", "available_for_pickup" -> ParcelStatus.OUT_FOR_DELIVERY
        "delivered" -> ParcelStatus.DELIVERED
        "failed_attempt", "exception" -> ParcelStatus.FAILED
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
