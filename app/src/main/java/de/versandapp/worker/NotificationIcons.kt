package de.versandapp.worker

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import de.versandapp.R

/**
 * Das farbige App-Logo als Bitmap für [NotificationCompat.Builder.setLargeIcon],
 * damit in der Benachrichtigung das neue Logo in Farbe erscheint. Das kleine
 * Status-Leisten-Icon muss dagegen einfarbig bleiben ([R.drawable.ic_stat_logo]).
 */
fun appLogoBitmap(context: Context): Bitmap? =
    runCatching {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(192, 192)
    }.getOrNull()
