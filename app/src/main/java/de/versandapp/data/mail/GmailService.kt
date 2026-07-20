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

    suspend fun searchShipmentMails(accessToken: String, maxResults: Int = 50): List<MailMessage> =
        withContext(Dispatchers.IO) {
            listMessageIds(accessToken, SHIPMENT_QUERY, maxResults).mapNotNull { id ->
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

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("&\\w+;"), " ")

    companion object {
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"

        /**
         * Bewusst breites Netz über die letzten 10 Tage: alle bekannten
         * Versand-Absender plus alle Betreffe rund um Lieferung/Zustellung.
         * Falsch-Treffer (Newsletter etc.) sortiert die KI-Auswertung aus.
         */
        private const val SHIPMENT_QUERY =
            "newer_than:10d (" +
                "from:(dhl OR deutschepost OR hermes OR myhermes OR dpd OR gls OR ups OR " +
                "fedex OR amazon OR shipment OR versand OR noreply-lieferung) OR " +
                "subject:(Sendung OR Sendungsverfolgung OR Sendungsnummer OR Trackingnummer OR " +
                "Zustellung OR Lieferung OR geliefert OR Paket OR versandt OR verschickt OR " +
                "unterwegs OR tracking OR shipped OR shipping OR delivery OR parcel)" +
                ")"
    }
}
