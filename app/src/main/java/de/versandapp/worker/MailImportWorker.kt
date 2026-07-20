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

        val imported = try {
            val suggestions = ShipmentMailScanner()
                .scan(token, app.settings.anthropicApiKey)
            val known = app.repository.trackedNumbers()
            suggestions
                .filter { it.trackingNumber !in known }
                .onEach { suggestion ->
                    app.repository.addParcel(
                        trackingNumber = suggestion.trackingNumber,
                        carrier = suggestion.carrier,
                        label = suggestion.sourceSubject.takeIf { it.isNotBlank() },
                    )
                }
                .size
        } catch (_: Exception) {
            return Result.retry()
        }

        if (imported > 0) notifyImported(imported)
        return Result.success()
    }

    private fun notifyImported(count: Int) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (count == 1) "1 neue Sendung aus Gmail importiert"
        else "$count neue Sendungen aus Gmail importiert"

        val notification = NotificationCompat.Builder(applicationContext, RefreshWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
