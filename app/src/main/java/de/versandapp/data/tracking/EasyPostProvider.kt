package de.versandapp.data.tracking

import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import java.io.IOException
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Base64
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
 * Günstige Tracking-Quelle über die EasyPost-Tracking-API (api.easypost.com).
 * Abrechnung pay-as-you-go (~0,02 $ je eindeutigem Standalone-Tracker, kein
 * Monatsminimum); wiederholtes Abrufen desselben Codes wird nicht erneut
 * berechnet.
 *
 * Es wird ein Standalone-Tracker angelegt (ohne Carrier-Angabe → EasyPost
 * erkennt den Dienst selbst aus der Nummer) und die Antwort direkt ausgewertet.
 *
 * Wie beim Ship24-/Claude-Provider gilt im Hintergrund ein Abendfenster +
 * 20 h Mindestabstand; der Aktualisieren-Knopf fragt jederzeit ab.
 */
class EasyPostProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean = true

    override val progressLabel: String = "EasyPost wird abgefragt …"

    override val minRefreshIntervalMs: Long = 20L * 60L * 60L * 1000L

    override fun isBackgroundRefreshAllowedNow(): Boolean =
        LocalTime.now().hour in 18..22

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put(
                    "tracker",
                    buildJsonObject { put("tracking_code", trackingNumber) },
                )
            }.toString()

            val auth = "Basic " + Base64.getEncoder().encodeToString("$apiKey:".toByteArray())
            val request = Request.Builder()
                .url("https://api.easypost.com/v2/trackers")
                .header("Authorization", auth)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (resp.code !in 200..299) {
                        throw TrackingException(
                            "EasyPost antwortete mit HTTP ${resp.code}: ${text.take(200)}"
                        )
                    }
                    text
                }
            } catch (e: IOException) {
                throw TrackingException("Netzwerkfehler bei der EasyPost-Abfrage", e)
            }

            parse(json.parseToJsonElement(response).jsonObject)
        }

    private fun parse(root: JsonObject): TrackingResult {
        val statusRaw = root["status"]?.jsonPrimitive?.contentOrNull
        if (statusRaw == "error") {
            throw TrackingException("EasyPost konnte diese Sendung nicht verfolgen")
        }

        val events = root["tracking_details"]?.jsonArray.orEmpty().mapNotNull { element ->
            val detail = element.jsonObject
            val message = detail["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: detail["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val location = detail["tracking_location"]?.jsonObject?.let { loc ->
                listOfNotNull(
                    loc["city"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    loc["country"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                ).joinToString(", ").takeIf { it.isNotBlank() }
            }
            TrackingUpdate(
                timestamp = parseTimestamp(detail["datetime"]?.jsonPrimitive?.contentOrNull),
                description = message,
                location = location,
                status = mapStatus(detail["status"]?.jsonPrimitive?.contentOrNull),
            )
        }.sortedBy { it.timestamp }

        val estimated = parseTimestampOrNull(root["est_delivery_date"]?.jsonPrimitive?.contentOrNull)

        if (events.isEmpty() && (statusRaw == null || statusRaw == "unknown" || statusRaw == "pre_transit")) {
            throw TrackingException(
                "Bei EasyPost liegen noch keine Daten zu dieser Sendung vor – " +
                    "bitte später erneut prüfen"
            )
        }

        return TrackingResult(
            status = mapStatus(statusRaw),
            events = events,
            estimatedDelivery = estimated,
        )
    }

    private fun mapStatus(status: String?): ParcelStatus = when (status) {
        "pre_transit" -> ParcelStatus.REGISTERED
        "in_transit" -> ParcelStatus.IN_TRANSIT
        "out_for_delivery", "available_for_pickup" -> ParcelStatus.OUT_FOR_DELIVERY
        "delivered" -> ParcelStatus.DELIVERED
        "return_to_sender", "failure", "cancelled", "error" -> ParcelStatus.FAILED
        else -> ParcelStatus.UNKNOWN
    }

    private fun parseTimestamp(value: String?): Long =
        parseTimestampOrNull(value) ?: System.currentTimeMillis()

    private fun parseTimestampOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }
}
