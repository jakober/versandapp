package de.versandapp.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import de.versandapp.data.carrier.CarrierDetector
import de.versandapp.data.model.Carrier

/**
 * Dialog zum Hinzufügen einer Sendung. Der Carrier wird anhand der
 * Trackingnummer vorgeschlagen und kann per Chip geändert werden.
 */
@Composable
fun AddParcelDialog(
    onDismiss: () -> Unit,
    onConfirm: (trackingNumber: String, carrier: Carrier, label: String?) -> Unit,
) {
    var trackingNumber by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var manualCarrier by remember { mutableStateOf<Carrier?>(null) }

    val normalized = CarrierDetector.normalize(trackingNumber)
    val suggestions = CarrierDetector.detect(normalized)
    val selectedCarrier = manualCarrier ?: suggestions.firstOrNull() ?: Carrier.OTHER
    val chipCarriers = (suggestions + Carrier.entries).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paket hinzufügen") },
        text = {
            Column {
                OutlinedTextField(
                    value = trackingNumber,
                    onValueChange = {
                        trackingNumber = it
                        manualCarrier = null
                    },
                    label = { Text("Trackingnummer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional), z. B. \"Neue Schuhe\"") },
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
                    chipCarriers.forEach { carrier ->
                        FilterChip(
                            selected = carrier == selectedCarrier,
                            onClick = { manualCarrier = carrier },
                            label = { Text(carrier.displayName) },
                        )
                    }
                }
                if (suggestions.isNotEmpty() && manualCarrier == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Automatisch erkannt: ${suggestions.first().displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalized.isNotEmpty(),
                onClick = { onConfirm(normalized, selectedCarrier, label.takeIf { it.isNotBlank() }) },
            ) {
                Text("Hinzufügen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
