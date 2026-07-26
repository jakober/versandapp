package de.versandapp.data.mail

import android.content.Context
import android.os.Build

/**
 * Liefert den on-device-Extractor (Gemini Nano), wenn das Gerät ihn tragen kann,
 * sonst null. Bewusst OHNE AICore-Import: Diese Datei lädt keine AICore-Klassen,
 * die [NanoMailExtractor]-Klasse (und damit AICore) wird erst berührt, wenn der
 * Zweig auf einem Gerät ≥ Android 12 tatsächlich ausgeführt wird. So bleibt die
 * App auf älteren Geräten (minSdk 26) lauffähig.
 *
 * Ob Nano wirklich bereit ist (Modell geladen, Gerät unterstützt), zeigt sich
 * erst beim ersten Aufruf – schlägt er fehl, greift der Regex-Parser als Notnetz.
 */
fun createNanoExtractor(context: Context): MailExtractor? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        NanoMailExtractor(context.applicationContext)
    } else {
        null
    }
