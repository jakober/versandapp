package de.versandapp.data.tracking

import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * Haupt-Tracking-Quelle: die Tracktry-API (api.tracktry.com) – der
 * Pay-as-you-go-Ableger von 17track. Deckt alle Dienstleister weltweit ab,
 * inklusive China; Abrechnung pro Sendung (Cent-Bereich), ohne Jahresvertrag.
 *
 * `POST /trackings/realtime` liefert Status + Verlauf in einem Aufruf und
 * erkennt den Carrier automatisch. Kostenloser Key auf tracktry.com.
 */
class TracktryProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("tracking_number", trackingNumber) }.toString()

            val request = Request.Builder()
                .url("https://api.tracktry.com/v1/trackings/realtime")
                .header("Tracktry-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val responseBody = try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string()
                    if (!response.isSuccessful) {
                        throw TrackingException(
                            "Tracktry antwortete mit HTTP ${response.code}: ${text?.take(200)}"
                        )
                    }
                    text ?: throw TrackingException("Leere Antwort von Tracktry")
                }
            } catch (e: IOException) {
                throw TrackingException("Netzwerkfehler bei der Tracktry-Abfrage", e)
            }

            parse(json.parseToJsonElement(responseBody).jsonObject)
        }

    private fun parse(root: JsonObject): TrackingResult {
        val metaCode = root["meta"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
        if (metaCode != null && metaCode != "200") {
            val msg = root["meta"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw TrackingException("Tracktry: ${msg ?: "Fehler $metaCode"}")
        }

        val item = extractItem(root["data"])
            ?: throw TrackingException(
                "Beim Dienstleister liegen noch keine Daten zu dieser Sendung vor"
            )

        val statusRaw = item["status"]?.jsonPrimitive?.contentOrNull
        if (statusRaw == null || statusRaw == "notfound") {
            throw TrackingException("Beim Dienstleister liegen noch keine Daten zu dieser Sendung vor")
        }

        // Verlauf kann unter origin_info und/oder destination_info liegen
        val events = listOf("origin_info", "destination_info")
            .mapNotNull { item[it]?.jsonObject }
            .flatMap { info ->
                (info["trackinfo"]?.jsonArray ?: info["track_info"]?.jsonArray).orEmpty()
            }
            .mapNotNull { element ->
                val event = element.jsonObject
                val description = (event["StatusDescription"]?.jsonPrimitive?.contentOrNull
                    ?: event["Details"]?.jsonPrimitive?.contentOrNull)
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TrackingUpdate(
                    timestamp = parseTimestamp(event["Date"]?.jsonPrimitive?.contentOrNull),
                    description = description,
                    location = event["Details"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != description },
                    status = mapStatus(event["checkpoint_status"]?.jsonPrimitive?.contentOrNull),
                )
            }
            .distinctBy { it.timestamp to it.description }
            .sortedBy { it.timestamp }

        return TrackingResult(status = mapStatus(statusRaw), events = events)
    }

    /** data kann Objekt, {items:[…]} oder Array sein – den ersten Eintrag herausziehen. */
    private fun extractItem(data: kotlinx.serialization.json.JsonElement?): JsonObject? = when (data) {
        is JsonObject -> data["items"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: data.takeIf { it.containsKey("status") || it.containsKey("tracking_number") }
        is JsonArray -> data.firstOrNull()?.jsonObject
        else -> null
    }

    private fun mapStatus(status: String?): ParcelStatus = when (status) {
        "inforeceived", "pending" -> ParcelStatus.REGISTERED
        "transit" -> ParcelStatus.IN_TRANSIT
        "pickup", "outfordelivery" -> ParcelStatus.OUT_FOR_DELIVERY
        "delivered" -> ParcelStatus.DELIVERED
        "undelivered", "exception", "expired" -> ParcelStatus.FAILED
        else -> ParcelStatus.UNKNOWN
    }

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(value.replace(" ", "T")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return System.currentTimeMillis()
    }
}
