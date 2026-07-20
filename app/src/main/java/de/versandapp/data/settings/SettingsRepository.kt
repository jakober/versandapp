package de.versandapp.data.settings

import android.content.Context

data class AppSettings(
    val anthropicApiKey: String,
    val dhlApiKey: String,
    val seventeenTrackApiKey: String,
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

    /** Key von api.17track.net – Haupt-Tracking-Quelle für alle Dienste weltweit. */
    var seventeenTrackApiKey: String
        get() = prefs.getString(KEY_17TRACK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_17TRACK, value.trim()).apply()

    /**
     * Merkt sich, dass der Nutzer Gmail bereits verknüpft hat – dann verbindet
     * sich die App still neu (ohne Google-Dialog) und der Hintergrund-Import
     * darf laufen.
     */
    var gmailLinked: Boolean
        get() = prefs.getBoolean(KEY_GMAIL_LINKED, false)
        set(value) = prefs.edit().putBoolean(KEY_GMAIL_LINKED, value).apply()

    fun current(): AppSettings = AppSettings(
        anthropicApiKey = anthropicApiKey,
        dhlApiKey = dhlApiKey,
        seventeenTrackApiKey = seventeenTrackApiKey,
    )

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_api_key"
        const val KEY_DHL = "dhl_api_key"
        const val KEY_17TRACK = "seventeen_track_api_key"
        const val KEY_GMAIL_LINKED = "gmail_linked"
    }
}
