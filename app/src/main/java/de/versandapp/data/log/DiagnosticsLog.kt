package de.versandapp.data.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ein einzelner Protokoll-Eintrag einer Abfrage. */
data class LogEntry(
    val time: Long,
    val parcel: String,
    val source: String,
    val message: String,
    val ok: Boolean,
)

/** Eine gescannte Mail – zum Nachlesen und Öffnen in Gmail (z. B. Abmelden). */
data class MailEntry(
    val time: Long,
    val from: String,
    val subject: String,
    val url: String,
)

/**
 * Einfaches, persistentes Diagnose-Protokoll: hält die letzten Abfrage-Versuche
 * (welche Quelle, Ergebnis oder echter Fehler) fest – damit man Fehler in Ruhe
 * nachlesen kann, statt sie im Live-Fenster zu verpassen. Erfasst wird jede
 * Quelle einzeln, also auch warum z. B. ChatGPT nichts gefunden hat.
 *
 * Zusätzlich werden die beim Postfach-Import gescannten Mails festgehalten,
 * damit der Nutzer sie selbst in Gmail öffnen kann (etwa um einen Abmelde-Link
 * anzuklicken).
 *
 * Bewusst als schlankes Singleton mit SharedPreferences-Persistenz (übersteht
 * App-Neustarts), passend zur Projektgröße.
 */
object DiagnosticsLog {

    private const val MAX = 300
    private const val MAX_MAILS = 150
    private const val PREFS = "versandapp_log"
    private const val KEY = "entries"
    private const val KEY_MAILS = "mails"
    private const val FIELD = "\t"

    private var prefs: android.content.SharedPreferences? = null

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _mails = MutableStateFlow<List<MailEntry>>(emptyList())
    val mails: StateFlow<List<MailEntry>> = _mails.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _entries.value = load(p)
        _mails.value = loadMails(p)
    }

    @Synchronized
    fun add(parcel: String, source: String, message: String, ok: Boolean) {
        val entry = LogEntry(
            time = System.currentTimeMillis(),
            parcel = sanitize(parcel),
            source = sanitize(source),
            message = sanitize(message).take(300),
            ok = ok,
        )
        val updated = (listOf(entry) + _entries.value).take(MAX)
        _entries.value = updated
        prefs?.edit()?.putString(KEY, serialize(updated))?.apply()
    }

    /**
     * Hält eine gescannte Mail fest. Bereits vorhandene Mails (gleicher Link)
     * werden nicht doppelt aufgenommen, damit die Liste bei überlappenden Scans
     * nicht vollläuft.
     */
    @Synchronized
    fun addMail(from: String, subject: String, url: String) {
        if (url.isBlank()) return
        if (_mails.value.any { it.url == url }) return
        val entry = MailEntry(
            time = System.currentTimeMillis(),
            from = sanitize(from).take(200),
            subject = sanitize(subject).take(200),
            url = sanitize(url),
        )
        val updated = (listOf(entry) + _mails.value).take(MAX_MAILS)
        _mails.value = updated
        prefs?.edit()?.putString(KEY_MAILS, serializeMails(updated))?.apply()
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        prefs?.edit()?.remove(KEY)?.apply()
    }

    @Synchronized
    fun clearMails() {
        _mails.value = emptyList()
        prefs?.edit()?.remove(KEY_MAILS)?.apply()
    }

    private fun sanitize(text: String): String =
        text.replace('\n', ' ').replace('\t', ' ')

    private fun serialize(list: List<LogEntry>): String =
        list.joinToString("\n") { e ->
            listOf(e.time.toString(), e.parcel, e.source, e.message, if (e.ok) "1" else "0")
                .joinToString(FIELD)
        }

    private fun serializeMails(list: List<MailEntry>): String =
        list.joinToString("\n") { e ->
            listOf(e.time.toString(), e.from, e.subject, e.url).joinToString(FIELD)
        }

    private fun load(prefs: android.content.SharedPreferences): List<LogEntry> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(FIELD)
            if (parts.size < 5) return@mapNotNull null
            LogEntry(
                time = parts[0].toLongOrNull() ?: return@mapNotNull null,
                parcel = parts[1],
                source = parts[2],
                message = parts[3],
                ok = parts[4] == "1",
            )
        }
    }

    private fun loadMails(prefs: android.content.SharedPreferences): List<MailEntry> {
        val raw = prefs.getString(KEY_MAILS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(FIELD)
            if (parts.size < 4) return@mapNotNull null
            MailEntry(
                time = parts[0].toLongOrNull() ?: return@mapNotNull null,
                from = parts[1],
                subject = parts[2],
                url = parts[3],
            )
        }
    }
}
