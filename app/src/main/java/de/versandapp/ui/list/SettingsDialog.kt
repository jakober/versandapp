package de.versandapp.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.versandapp.data.settings.AppSettings

/**
 * Einstellungen für den Anthropic-API-Key. Optional – ohne Key läuft die App
 * mit Demo-Daten bzw. dem lokalen Regex-Mail-Parser.
 */
@Composable
fun SettingsDialog(
    initial: AppSettings,
    testPushInitial: Boolean,
    onDismiss: () -> Unit,
    onTestNow: () -> Unit,
    onTestPushChange: (Boolean) -> Unit,
    onSave: (anthropicApiKey: String, dhlApiKey: String, ship24ApiKey: String) -> Unit,
) {
    var anthropicKey by remember { mutableStateOf(initial.anthropicApiKey) }
    var dhlKey by remember { mutableStateOf(initial.dhlApiKey) }
    var ship24Key by remember { mutableStateOf(initial.ship24ApiKey) }
    var testPush by remember { mutableStateOf(testPushInitial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it },
                    label = { Text("Anthropic API-Key (Claude)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Für die KI-Mail-Erkennung und die Online-Suche aller Dienste " +
                        "ohne eigene API (Hermes, DPD, GLS, Auslandspakete inkl. China). " +
                        "Key erstellen: console.anthropic.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = dhlKey,
                    onValueChange = { dhlKey = it },
                    label = { Text("DHL API-Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kostenloser Key von developer.dhl.com – DHL-/Post-Sendungen " +
                        "werden damit zuverlässig über die offizielle API geprüft, " +
                        "alle anderen Dienste weiterhin über die Online-Suche.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = ship24Key,
                    onValueChange = { ship24Key = it },
                    label = { Text("Ship24 API-Key (Notnagel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Optionaler kostenpflichtiger Notnagel: wird nur abgefragt, " +
                        "wenn DHL-API und Online-Suche nichts gefunden haben – so " +
                        "bleibt das knappe Ship24-Kontingent geschont. Leer lassen, " +
                        "wenn du kein Ship24 nutzen willst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Benachrichtigungen testen",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTestNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Jetzt Test-Push senden")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sollte sofort als Benachrichtigung erscheinen. Kommt nichts, " +
                        "sind Benachrichtigungen für die App in den Android-" +
                        "Einstellungen deaktiviert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Alle 2 Minuten senden",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Prüft auch die Hintergrund-Zustellung. Nicht vergessen " +
                                "wieder auszuschalten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = testPush,
                        onCheckedChange = {
                            testPush = it
                            onTestPushChange(it)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(anthropicKey, dhlKey, ship24Key) }) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
