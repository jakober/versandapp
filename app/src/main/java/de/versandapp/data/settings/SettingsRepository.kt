package de.versandapp.data.settings

import android.content.Context

data class AppSettings(
    val anthropicApiKey: String,
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

    fun current(): AppSettings = AppSettings(
        anthropicApiKey = anthropicApiKey,
    )

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_api_key"
    }
}
