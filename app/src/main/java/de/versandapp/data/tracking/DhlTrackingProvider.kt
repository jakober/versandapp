package de.versandapp.data.tracking

import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Anbindung an die DHL "Unified Shipment Tracking" API.
 *
 * Einen kostenlosen API-Key gibt es unter https://developer.dhl.com
 * (App anlegen, Produkt "Shipment Tracking – Unified" abonnieren).
 * Der Key wird beim App-Start aus BuildConfig/Einstellungen gereicht;
 * ohne Key fällt die App auf den [DemoTrackingProvider] zurück.
 */
class DhlTrackingProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TrackingProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(carrier: Carrier): Boolean =
        carrier == Carrier.DHL || carrier == Carrier.DEUTSCHE_POST

    override val progressLabel: String = "DHL-API wird abgefragt …"

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api-eu.dhl.com/track/shipments?trackingNumber=$trackingNumber")
                .header("DHL-API-Key", apiKey)
                .build()

            val body = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw TrackingException("DHL API antwortete mit HTTP ${response.code}")
                    }
                    response.body?.string()
                        ?: throw TrackingException("Leere Antwort der DHL API")
                }
            } catch (e: IOException) {
                throw TrackingException("Netzwerkfehler bei der DHL-Abfrage", e)
            }

            parse(body)
        }

    private fun parse(body: String): TrackingResult {
        val root = json.parseToJsonElement(body).jsonObject
        val shipment = root["shipments"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw TrackingException("Keine Sendung in der DHL-Antwort gefunden")

        val events = shipment["events"]?.jsonArray.orEmpty().mapNotNull { element ->
            val event = element.jsonObject
            val timestamp = event["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null
            TrackingUpdate(
                timestamp = parseTimestamp(timestamp),
                description = event["description"]?.jsonPrimitive?.content ?: "Statusupdate",
                location = event["location"]?.jsonObject
                    ?.get("address")?.jsonObject
                    ?.get("addressLocality")?.jsonPrimitive?.content,
                status = mapStatus(event["statusCode"]?.jsonPrimitive?.content),
            )
        }.sortedBy { it.timestamp }

        val statusCode = shipment["status"]?.jsonObject
            ?.get("statusCode")?.jsonPrimitive?.content

        return TrackingResult(
            status = mapStatus(statusCode),
            events = events,
        )
    }

    private fun parseTimestamp(value: String): Long = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    private fun mapStatus(statusCode: String?): ParcelStatus = when (statusCode) {
        "pre-transit" -> ParcelStatus.REGISTERED
        "transit" -> ParcelStatus.IN_TRANSIT
        "delivered" -> ParcelStatus.DELIVERED
        "failure" -> ParcelStatus.FAILED
        else -> ParcelStatus.UNKNOWN
    }
}
