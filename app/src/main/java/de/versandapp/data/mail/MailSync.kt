package de.versandapp.data.mail

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import de.versandapp.VersandApp
import kotlinx.coroutines.tasks.await

/**
 * Gemeinsame Postfach-Synchronisierung für „↻"/Pull-to-Refresh und den
 * Hintergrund-Worker: still ein Gmail-Token holen, Mails scannen, neue Sendungen
 * anlegen und den Status bekannter Sendungen aus den Mails fortschreiben.
 *
 * Seit dem Umstieg auf „nur Postfach" ist das die zentrale Update-Quelle der App
 * (kein Online-Tracking mehr).
 */
object MailSync {

    data class Result(val imported: Int, val updated: Int, val ok: Boolean)

    /** Holt ohne UI ein Gmail-Zugriffstoken – klappt nur nach einmaliger Freigabe. */
    suspend fun silentToken(context: Context): String? {
        val authResult = try {
            Identity.getAuthorizationClient(context)
                .authorize(
                    AuthorizationRequest.builder()
                        .setRequestedScopes(listOf(Scope(GmailService.SCOPE_READONLY)))
                        .build()
                )
                .await()
        } catch (_: Exception) {
            return null
        }
        if (authResult.hasResolution()) return null // Nutzer müsste neu zustimmen
        return authResult.accessToken
    }

    /**
     * Scannt Mails ab [afterEpochSeconds], legt neue Sendungen an und zieht bei
     * bekannten den Status nach. Aktualisiert den „letzter Scan"-Zeitpunkt.
     */
    suspend fun sync(
        app: VersandApp,
        afterEpochSeconds: Long,
    ): Result {
        if (!app.settings.gmailLinked) return Result(0, 0, ok = false)
        val token = silentToken(app) ?: return Result(0, 0, ok = false)

        val suggestions = try {
            app.buildMailScanner().scan(gmailAccessToken = token, afterEpochSeconds = afterEpochSeconds)
        } catch (_: Exception) {
            return Result(0, 0, ok = false)
        }

        val known = app.repository.trackedNumbers()
        var imported = 0
        suggestions.filter { it.trackingNumber !in known }.forEach { s ->
            app.repository.addParcel(
                trackingNumber = s.trackingNumber,
                carrier = s.carrier,
                label = s.sourceSubject.takeIf { it.isNotBlank() },
                initialStatus = s.initialStatus,
            )
            imported++
        }
        var updated = 0
        suggestions.filter { it.trackingNumber in known }.forEach { s ->
            val status = s.initialStatus ?: return@forEach
            if (app.repository.updateStatusFromMail(s.trackingNumber, status)) updated++
        }

        app.settings.lastMailImportEpochSeconds = System.currentTimeMillis() / 1000
        return Result(imported = imported, updated = updated, ok = true)
    }
}
