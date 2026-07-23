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
            // Die generische Unified-API findet deutsche Paketnummern oft nur mit
            // service-Parameter. Der Reihe nach probieren, bis eine Variante eine
            // Sendung liefert.
            val base = "https://api-eu.dhl.com/track/shipments?trackingNumber=$trackingNumber"
            val urls = listOf(
                base,
                "$base&service=parcel-de",
                "$base&service=post-de",
            )
            var lastError: TrackingException? = null
            for (url in urls) {
                try {
                    return@withContext parse(fetch(url))
                } catch (e: TrackingException) {
                    lastError = e
                }
            }
            throw lastError ?: TrackingException("DHL: keine Sendung gefunden")
        }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("DHL-API-Key", apiKey)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    // DHL liefert bei Fehlern einen erklärenden Text mit (z. B. Grund
                    // für 401) – mit ins Protokoll nehmen, statt nur den Statuscode.
                    throw TrackingException(
                        "DHL API HTTP ${response.code}: ${text?.take(200)?.replace("\n", " ")}"
                    )
                }
                text ?: throw TrackingException("Leere Antwort der DHL API")
            }
        } catch (e: IOException) {
            throw TrackingException("Netzwerkfehler bei der DHL-Abfrage", e)
        }
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

        val estimated = shipment["estimatedTimeOfDelivery"]?.jsonPrimitive?.content
            ?.let { parseTimestampOrNull(it) }

        return TrackingResult(
            status = mapStatus(statusCode),
            events = events,
            estimatedDelivery = estimated,
        )
    }

    private fun parseTimestamp(value: String): Long =
        parseTimestampOrNull(value) ?: System.currentTimeMillis()

    private fun parseTimestampOrNull(value: String): Long? = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun mapStatus(statusCode: String?): ParcelStatus = when (statusCode) {
        "pre-transit" -> ParcelStatus.REGISTERED
        "transit" -> ParcelStatus.IN_TRANSIT
        "delivered" -> ParcelStatus.DELIVERED
        "failure" -> ParcelStatus.FAILED
        else -> ParcelStatus.UNKNOWN
    }
}
