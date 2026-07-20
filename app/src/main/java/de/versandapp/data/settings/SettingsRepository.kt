package de.versandapp.data.settings

import android.content.Context

data class AppSettings(
    val anthropicApiKey: String,
    val dhlApiKey: String,
    val ship24ApiKey: String,
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

    fun current(): AppSettings = AppSettings(
        anthropicApiKey = anthropicApiKey,
        dhlApiKey = dhlApiKey,
        ship24ApiKey = ship24ApiKey,
    )

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_api_key"
        const val KEY_DHL = "dhl_api_key"
        const val KEY_SHIP24 = "ship24_api_key"
        const val KEY_GMAIL_LINKED = "gmail_linked"
        const val KEY_LAST_MAIL_IMPORT = "last_mail_import_epoch"
    }
}
