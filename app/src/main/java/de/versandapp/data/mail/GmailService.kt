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
    /** RFC822 „Message-ID"-Header – für den Deep-Link zum Öffnen in Gmail. */
    val messageId: String = "",
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
        maxResults: Int = 60,
    ): List<MailMessage> =
        withContext(Dispatchers.IO) {
            // Zeitfenster (erster Scan: letzte 48 h; danach ab dem letzten Scan)
            // kombiniert mit einem INHALTS-Filter: Es werden nur Mails geladen,
            // die überhaupt ein Versand-Stichwort enthalten. Das bleibt bewusst
            // ABSENDER-unabhängig (der Filter greift auch im Volltext/Body), hält
            // aber reine Newsletter, Rechnungen und Werbung ohne jeden
            // Versandbezug von der KI fern – sonst zieht sie dort fälschlich
            // Bestell-/Rechnungsnummern als „Trackingnummer" heraus.
            val window = if (afterEpochSeconds > 0L) "after:$afterEpochSeconds" else "newer_than:2d"
            val query = "$window $SHIPMENT_KEYWORDS"
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
            messageId = headers["Message-ID"] ?: headers["Message-Id"] ?: "",
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
        val allUrls = urlRegex.findAll(html).map { it.value }.distinct().toList()
        // Tracking-relevante Links nach vorne, reines Rauschen (Sprachumschalter,
        // Bilder, CDN) weglassen. So überleben die Tracking-Links das spätere
        // Kürzen für die KI (Mails wie DHL packen die echten Links weit unten).
        val tracking = allUrls.filter { trackingHintRegex.containsMatchIn(it) }
        val rest = allUrls.filter { !trackingHintRegex.containsMatchIn(it) && !noiseRegex.containsMatchIn(it) }
        val urls = tracking + rest
        val visible = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&\\w+;"), " ")
        return if (urls.isEmpty()) visible else visible + "\n\nLinks:\n" + urls.joinToString("\n")
    }

    private val urlRegex = Regex("https?://[^\\s\"'<>]+")

    /** Links, die auf eine Trackingnummer hindeuten (Parameter oder Pfadteile). */
    private val trackingHintRegex = Regex(
        "(?i)(idc|piececode|piece|nummer|tracknum|trackingnumber|tracking_number|" +
            "sendungsnummer|sendungsverfolgung|verfolgen|/track)"
    )

    /** Reine Rausch-Links: Sprachumschalter (?u=…), Bilder/Assets, bekannte CDNs. */
    private val noiseRegex = Regex(
        "(?i)(\\?u=|\\.(png|gif|jpe?g|css|svg|woff2?)(\\?|$)|cdn\\.mailix\\.com|w3\\.org)"
    )

    companion object {
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"

        /**
         * Baut einen Link, der die konkrete Mail in Gmail öffnet (App oder Web).
         * Bevorzugt die stabile RFC822-Message-ID-Suche; fällt sonst auf die
         * Gmail-interne Nachrichten-ID zurück. So kann der Nutzer die Mail selbst
         * ansehen und z. B. einen Abmelde-Link anklicken.
         */
        fun gmailDeepLink(mail: MailMessage): String {
            val rid = mail.messageId.trim().removePrefix("<").removeSuffix(">")
            return if (rid.isNotBlank()) {
                "https://mail.google.com/mail/u/0/#search/" +
                    java.net.URLEncoder.encode("rfc822msgid:$rid", "UTF-8")
            } else {
                "https://mail.google.com/mail/u/0/#all/${mail.id}"
            }
        }

        /**
         * Inhalts-Vorfilter für die Gmail-Suche (matcht auch im Mail-Body, also
         * NICHT absenderabhängig). Eine Mail muss mindestens eines dieser
         * Versand-Stichwörter enthalten, um überhaupt zur KI-Analyse zu kommen.
         * Bewusst breit gehalten, damit keine echte Versandmail durchrutscht,
         * aber ohne Bezug zu Sendungen (Newsletter, Rechnungen) fällt sofort weg.
         */
        private const val SHIPMENT_KEYWORDS = "(" +
            "sendungsnummer OR sendungsverfolgung OR trackingnummer OR paketnummer OR " +
            "tracking OR versandt OR verschickt OR versandbestätigung OR versandbestaetigung OR " +
            "versandbenachrichtigung OR unterwegs OR zustellung OR zugestellt OR ausgeliefert OR " +
            "lieferung OR sendung OR paket OR päckchen OR shipped OR shipment OR " +
            "delivery OR delivered OR parcel OR dhl OR hermes OR dpd OR gls OR ups OR fedex OR amazon" +
            ")"
    }
}
