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
import kotlinx.serialization.json.contentOrNull
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
 * Ship24 hat zwei Plan-Typen mit getrennten Endpunkten:
 *  - "per-shipment": `POST /trackers/track` (idempotent – erster Aufruf legt
 *    den Tracker an und zählt aufs Kontingent, Folgeaufrufe sind frei)
 *  - "per-call": `POST /tracking/search` (jeder Aufruf zählt)
 * Der Provider versucht zuerst den per-shipment-Endpunkt und weicht bei
 * "no_active_subscription" automatisch auf den per-call-Endpunkt aus –
 * so funktioniert die App mit beiden Plan-Typen.
 */
class Ship24Provider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult =
        withContext(Dispatchers.IO) {
            var response = post("trackers/track", trackingNumber)
            if (response.code == 422 && response.body.contains("no_active_subscription")) {
                // Konto hat einen per-call-Plan → anderen Endpunkt nutzen
                response = post("tracking/search", trackingNumber)
            }
            if (response.code !in 200..299) {
                throw TrackingException(
                    "Ship24 antwortete mit HTTP ${response.code}: ${response.body.take(200)}"
                )
            }
            parse(json.parseToJsonElement(response.body).jsonObject)
        }

    private data class ApiResponse(val code: Int, val body: String)

    private fun post(path: String, trackingNumber: String): ApiResponse {
        val body = buildJsonObject { put("trackingNumber", trackingNumber) }.toString()

        val request = Request.Builder()
            .url("https://api.ship24.com/public/v1/$path")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                ApiResponse(response.code, response.body?.string() ?: "")
            }
        } catch (e: IOException) {
            throw TrackingException("Netzwerkfehler bei der Ship24-Abfrage", e)
        }
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
            val description = event["status"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TrackingUpdate(
                timestamp = parseTimestamp(event["occurrenceDatetime"]?.jsonPrimitive?.contentOrNull),
                description = description,
                location = event["location"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                status = mapMilestone(event["statusMilestone"]?.jsonPrimitive?.contentOrNull),
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
