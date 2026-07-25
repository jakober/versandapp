package de.versandapp.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus

/**
 * Dialog zum Bearbeiten eines vorhandenen Pakets: Name (Label), Dienstleister
 * und Status korrigieren. Die Trackingnummer bleibt fest. Der manuelle Status
 * hilft, falsch erkannte Zustände (z. B. fälschlich „Zugestellt") zu korrigieren.
 */
@Composable
fun EditParcelDialog(
    trackingNumber: String,
    initialLabel: String?,
    initialCarrier: Carrier,
    initialStatus: ParcelStatus,
    onDismiss: () -> Unit,
    onConfirm: (label: String?, carrier: Carrier, status: ParcelStatus) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel ?: "") }
    var carrier by remember { mutableStateOf(initialCarrier) }
    var status by remember { mutableStateOf(initialStatus) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paket bearbeiten") },
        text = {
            Column {
                Text(
                    trackingNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Dienstleister", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Carrier.entries.forEach { entry ->
                        FilterChip(
                            selected = entry == carrier,
                            onClick = { carrier = entry },
                            label = { Text(entry.displayName) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Status", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ParcelStatus.entries.forEach { entry ->
                        FilterChip(
                            selected = entry == status,
                            onClick = { status = entry },
                            label = { Text(entry.displayName) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.takeIf { it.isNotBlank() }, carrier, status) }) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
