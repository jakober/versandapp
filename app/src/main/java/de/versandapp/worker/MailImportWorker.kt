package de.versandapp.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import de.versandapp.R
import de.versandapp.VersandApp
import de.versandapp.data.mail.GmailService
import de.versandapp.data.mail.ShipmentMailScanner
import de.versandapp.ui.MainActivity
import kotlinx.coroutines.tasks.await

/**
 * Ruft regelmäßig automatisch neue Versand-Mails ab (Planung siehe
 * [VersandApp.onCreate]), importiert gefundene Sendungen und benachrichtigt
 * den Nutzer darüber.
 *
 * Läuft nur, wenn der Nutzer Gmail einmal in der App verknüpft hat – danach
 * liefert Google das Zugriffstoken still, ohne Anmeldedialog.
 */
class MailImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VersandApp ?: return Result.failure()
        if (!app.settings.gmailLinked) return Result.success()

        // Stilles Token ohne UI – klappt nur, wenn die Berechtigung schon erteilt wurde
        val authResult = try {
            Identity.getAuthorizationClient(applicationContext)
                .authorize(
                    AuthorizationRequest.builder()
                        .setRequestedScopes(listOf(Scope(GmailService.SCOPE_READONLY)))
                        .build()
                )
                .await()
        } catch (_: Exception) {
            return Result.retry()
        }
        if (authResult.hasResolution()) return Result.success() // Nutzer müsste neu zustimmen
        val token = authResult.accessToken ?: return Result.success()

        val result = try {
            val suggestions = ShipmentMailScanner().scan(
                gmailAccessToken = token,
                afterEpochSeconds = app.settings.lastMailImportEpochSeconds,
            )
            val known = app.repository.trackedNumbers()
            // Neue Sendungen anlegen.
            var imported = 0
            suggestions.filter { it.trackingNumber !in known }.forEach { suggestion ->
                app.repository.addParcel(
                    trackingNumber = suggestion.trackingNumber,
                    carrier = suggestion.carrier,
                    label = suggestion.sourceSubject.takeIf { it.isNotBlank() },
                    initialStatus = suggestion.initialStatus,
                )
                imported++
            }
            // Bereits verfolgte Sendungen: Status aus der Mail nachziehen
            // (z. B. Amazon „in Zustellung"/„zugestellt").
            var updated = 0
            suggestions.filter { it.trackingNumber in known }.forEach { suggestion ->
                val status = suggestion.initialStatus ?: return@forEach
                if (app.repository.updateStatusFromMail(suggestion.trackingNumber, status)) updated++
            }
            imported to updated
        } catch (_: Exception) {
            return Result.retry()
        }
        val (imported, updated) = result

        // Ab jetzt nur noch Mails ab diesem Zeitpunkt berücksichtigen
        app.settings.lastMailImportEpochSeconds = System.currentTimeMillis() / 1000

        // Nachts (22–6 Uhr) still bleiben.
        if (RefreshWorker.isQuietHoursNow()) return Result.success()

        // Bei Neuigkeiten immer melden; „nichts Neues" nur, wenn der Nutzer
        // sich jede Prüfung bestätigen lassen will.
        if (imported > 0 || updated > 0) {
            notifyImported(imported, updated)
        } else if (app.settings.notifyAlways) {
            notifyNothingNew()
        }
        return Result.success()
    }

    private fun notifyImported(imported: Int, updated: Int) {
        val parts = buildList {
            if (imported == 1) add("1 neue Sendung importiert")
            else if (imported > 1) add("$imported neue Sendungen importiert")
            if (updated == 1) add("1 Sendung aktualisiert")
            else if (updated > 1) add("$updated Sendungen aktualisiert")
        }
        notify(parts.joinToString(" · ").ifEmpty { "Postfach geprüft" })
    }

    private fun notifyNothingNew() {
        notify("Postfach geprüft – nichts Neues")
    }

    private fun notify(text: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, RefreshWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setLargeIcon(appLogoBitmap(applicationContext))
            .setContentTitle("Mail-Import")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val WORK_NAME = "mail_import"
        private const val NOTIFICATION_ID = 1_000_001
    }
}
