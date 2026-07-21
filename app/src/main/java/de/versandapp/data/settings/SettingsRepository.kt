package de.versandapp.data.settings

import android.content.Context

data class AppSettings(
    val anthropicApiKey: String,
    val dhlApiKey: String,
    val ship24ApiKey: String,
    val openAiApiKey: String,
    val openAiTrackingModel: String,
)

/**
 * Speichert API-Keys lokal in SharedPreferences, damit sie in der App
 * konfiguriert werden können statt im Code.
 *
 * Hinweis: Ein API-Key in einer verteilten App ist grundsätzlich auslesbar –
 * für den Eigenbedarf in Ordnung, für eine Store-Veröffentlichung gehört die
 * Claude-Anbindung hinter einen eigenen Backend-Proxy.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("versandapp_settings", Context.MODE_PRIVATE)

    var anthropicApiKey: String
        get() = prefs.getString(KEY_ANTHROPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ANTHROPIC, value.trim()).apply()

    /** Kostenloser Key von developer.dhl.com – nur für DHL-/Post-Sendungen. */
    var dhlApiKey: String
        get() = prefs.getString(KEY_DHL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DHL, value.trim()).apply()

    /** Key von ship24.com – kostenpflichtiger Notnagel ganz am Ende der Kette. */
    var ship24ApiKey: String
        get() = prefs.getString(KEY_SHIP24, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SHIP24, value.trim()).apply()

    /**
     * Key von platform.openai.com. Ist er gesetzt, fragt zusätzlich ChatGPT
     * (OpenAI) den Sendungsstatus per Web-Suche ab – als weitere Quelle in der
     * Kette, wenn DHL-API und Claude nichts gefunden haben.
     */
    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI, value.trim()).apply()

    /** Schnelles OpenAI-Modell für die Online-Sendungssuche (anpassbar). */
    var openAiTrackingModel: String
        get() = prefs.getString(KEY_OPENAI_TRACK_MODEL, DEFAULT_TRACK_MODEL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_TRACK_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_TRACK_MODEL, value.trim()).apply()

    /**
     * Merkt sich, dass der Nutzer Gmail bereits verknüpft hat – dann verbindet
     * sich die App still neu (ohne Google-Dialog) und der Hintergrund-Import
     * darf laufen.
     */
    var gmailLinked: Boolean
        get() = prefs.getBoolean(KEY_GMAIL_LINKED, false)
        set(value) = prefs.edit().putBoolean(KEY_GMAIL_LINKED, value).apply()

    /**
     * Unix-Sekunden des letzten Mail-Imports. 0 = noch nie importiert – dann
     * werden die letzten 24 h durchsucht, danach nur Mails seit diesem Zeitpunkt.
     */
    var lastMailImportEpochSeconds: Long
        get() = prefs.getLong(KEY_LAST_MAIL_IMPORT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_MAIL_IMPORT, value).apply()

    /** Testmodus: schickt alle ~2 Minuten eine Test-Push (zum Prüfen). */
    var testPushEnabled: Boolean
        get() = prefs.getBoolean(KEY_TEST_PUSH, false)
        set(value) = prefs.edit().putBoolean(KEY_TEST_PUSH, value).apply()

    /**
     * true = nach jedem Hintergrundlauf benachrichtigen (auch „nichts Neues"),
     * false = nur bei echten Neuigkeiten. Nachts (22–6 Uhr) kommt in beiden
     * Fällen keine Benachrichtigung.
     */
    var notifyAlways: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ALWAYS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ALWAYS, value).apply()

    /** Zugestellte Pakete nach so vielen Tagen ins Archiv verschieben (Default 14). */
    var archiveAfterDays: Int
        get() = prefs.getInt(KEY_ARCHIVE_DAYS, 14)
        set(value) = prefs.edit().putInt(KEY_ARCHIVE_DAYS, value.coerceAtLeast(1)).apply()

    fun current(): AppSettings = AppSettings(
        anthropicApiKey = anthropicApiKey,
        dhlApiKey = dhlApiKey,
        ship24ApiKey = ship24ApiKey,
        openAiApiKey = openAiApiKey,
        openAiTrackingModel = openAiTrackingModel,
    )

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_api_key"
        const val KEY_DHL = "dhl_api_key"
        const val KEY_SHIP24 = "ship24_api_key"
        const val KEY_OPENAI = "openai_api_key"
        const val KEY_OPENAI_TRACK_MODEL = "openai_tracking_model"
        const val KEY_GMAIL_LINKED = "gmail_linked"
        const val KEY_LAST_MAIL_IMPORT = "last_mail_import_epoch"
        const val KEY_TEST_PUSH = "test_push_enabled"
        const val KEY_NOTIFY_ALWAYS = "notify_always"
        const val KEY_ARCHIVE_DAYS = "archive_after_days"

        // Vorbelegtes Modell – in den Einstellungen anpassbar, falls der Name
        // im OpenAI-Konto abweicht.
        const val DEFAULT_TRACK_MODEL = "gpt-5.4-mini"
    }
}
