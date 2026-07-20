package de.versandapp.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onDismiss: () -> Unit,
    onSave: (anthropicApiKey: String, dhlApiKey: String) -> Unit,
) {
    var anthropicKey by remember { mutableStateOf(initial.anthropicApiKey) }
    var dhlKey by remember { mutableStateOf(initial.dhlApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column {
                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it },
                    label = { Text("Anthropic API-Key (Claude)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Aktiviert die KI-Mail-Erkennung und die Online-Statusabfrage " +
                        "per Web-Suche (automatisch 1× täglich abends für offene " +
                        "Pakete, manuell jederzeit über ↻). " +
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(anthropicKey, dhlKey) }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
