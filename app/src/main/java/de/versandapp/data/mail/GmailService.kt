package de.versandapp.data.mail

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class MailMessage(
    val id: String,
    val subject: String,
    val from: String,
    val body: String,
)

class MailException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimaler Client für die Gmail REST API (Scope: gmail.readonly).
 * Sucht Versand-Mails bekannter Absender und lädt deren Inhalt –
 * die Verarbeitung passiert danach komplett auf dem Gerät.
 */
class GmailService(private val client: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param afterEpochSeconds Nur Mails ab diesem Zeitpunkt (Unix-Sekunden);
     *   0 = die letzten 24 Stunden.
     */
    suspend fun searchShipmentMails(
        accessToken: String,
        afterEpochSeconds: Long = 0L,
        maxResults: Int = 50,
    ): List<MailMessage> =
        withContext(Dispatchers.IO) {
            val timeFilter = if (afterEpochSeconds > 0L) {
                "after:$afterEpochSeconds"
            } else {
                "newer_than:1d"
            }
            val query = "$timeFilter $SHIPMENT_QUERY"
            listMessageIds(accessToken, query, maxResults).mapNotNull { id ->
                runCatching { getMessage(accessToken, id) }.getOrNull()
            }
        }

    private fun listMessageIds(accessToken: String, query: String, maxResults: Int): List<String> {
        val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&maxResults=$maxResults"
        val root = getJson(accessToken, url)
        return root["messages"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.content
        }
    }

    private fun getMessage(accessToken: String, id: String): MailMessage {
        val root = getJson(
            accessToken,
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full",
        )
        val payload = root["payload"]?.jsonObject
            ?: throw MailException("Nachricht $id ohne Inhalt")

        val headers = payload["headers"]?.jsonArray.orEmpty().associate {
            val header = it.jsonObject
            (header["name"]?.jsonPrimitive?.content ?: "") to
                (header["value"]?.jsonPrimitive?.content ?: "")
        }

        return MailMessage(
            id = id,
            subject = headers["Subject"] ?: "",
            from = headers["From"] ?: "",
            body = extractText(payload),
        )
    }

    private fun getJson(accessToken: String, url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw MailException("Gmail API antwortete mit HTTP ${response.code}")
                }
                response.body?.string() ?: throw MailException("Leere Antwort der Gmail API")
            }
        } catch (e: IOException) {
            throw MailException("Netzwerkfehler bei der Gmail-Abfrage", e)
        }
        return json.parseToJsonElement(body).jsonObject
    }

    /** Sammelt rekursiv alle text/plain- und text/html-Teile einer Nachricht ein. */
    private fun extractText(part: JsonObject): String {
        val texts = mutableListOf<String>()

        fun visit(node: JsonObject) {
            val mimeType = node["mimeType"]?.jsonPrimitive?.content ?: ""
            val data = node["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content
            if (data != null && (mimeType.startsWith("text/plain") || mimeType.startsWith("text/html"))) {
                runCatching {
                    val decoded = String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
                    texts += if (mimeType.startsWith("text/html")) stripHtml(decoded) else decoded
                }
            }
            node["parts"]?.jsonArray?.forEach { visit(it.jsonObject) }
        }
        visit(part)
        return texts.joinToString("\n")
    }

    /**
     * Wandelt HTML in Text. WICHTIG: Trackingnummern stecken oft nur im Link
     * eines Buttons (z. B. "Sendungsverfolgung" → href mit idc=/piececode=),
     * nicht im sichtbaren Text. Deshalb werden alle URLs zuerst eingesammelt
     * und ans Ende angehängt, damit sie beim Bereinigen nicht verloren gehen.
     */
    private fun stripHtml(html: String): String {
        val urls = urlRegex.findAll(html).map { it.value }.distinct().toList()
        val visible = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&\\w+;"), " ")
        return if (urls.isEmpty()) visible else visible + "\n\nLinks:\n" + urls.joinToString("\n")
    }

    private val urlRegex = Regex("https?://[^\\s\"'<>]+")

    companion object {
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"

        /**
         * Bewusst breites Netz: alle bekannten Versand-Absender plus alle
         * Betreffe rund um Lieferung/Zustellung. Das Zeitfenster wird davor
         * gesetzt (24 h bzw. seit letztem Import). Falsch-Treffer (Newsletter
         * etc.) sortiert die KI-Auswertung aus.
         */
        private const val SHIPMENT_QUERY =
            "(" +
                "from:(dhl OR deutschepost OR hermes OR myhermes OR dpd OR gls OR ups OR " +
                "fedex OR amazon OR aliexpress OR temu OR shein OR cainiao OR yanwen OR " +
                "shipment OR versand OR noreply-lieferung) OR " +
                "subject:(Sendung OR Sendungsverfolgung OR Sendungsnummer OR Trackingnummer OR " +
                "Zustellung OR Lieferung OR geliefert OR Paket OR versandt OR verschickt OR " +
                "unterwegs OR tracking OR shipped OR shipping OR delivery OR parcel)" +
                ")"
    }
}
