package de.versandapp.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.versandapp.ui.RefreshItemState
import de.versandapp.ui.RefreshProgress
import de.versandapp.ui.components.CarrierBadge

/**
 * Live-Fortschritt der manuellen Online-Prüfung: zeigt pro Sendung, ob sie
 * wartet, gerade von Claude recherchiert wird oder was das Ergebnis war –
 * inklusive einer Meldung, wenn nichts gefunden wurde.
 */
@Composable
fun RefreshProgressDialog(
    progress: RefreshProgress,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (progress.finished) "Online-Prüfung abgeschlossen"
                else "Online-Prüfung läuft…"
            )
        },
        text = {
            Column {
                if (progress.items.isEmpty()) {
                    Text(
                        "Keine offenen Sendungen zu prüfen – alle Pakete sind " +
                            "bereits zugestellt oder die Liste ist leer.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        items(progress.items, key = { it.parcelId }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CarrierBadge(carrier = item.carrier, size = 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = when (val state = item.state) {
                                            RefreshItemState.Waiting -> "Wartet …"
                                            RefreshItemState.Checking -> "Claude recherchiert online …"
                                            is RefreshItemState.Done -> state.text
                                            is RefreshItemState.SkippedItem -> state.text
                                            is RefreshItemState.Error -> state.text
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (item.state) {
                                            is RefreshItemState.Error -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                StateIcon(item.state)
                            }
                        }
                    }
                    if (!progress.finished) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Eine Online-Recherche dauert pro Sendung bis zu einer " +
                                "Minute. Du kannst das Fenster schließen – die Prüfung " +
                                "läuft im Hintergrund weiter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (progress.finished) "Fertig" else "Schließen")
            }
        },
    )
}

@Composable
private fun StateIcon(state: RefreshItemState) {
    when (state) {
        RefreshItemState.Waiting -> Icon(
            Icons.Filled.HourglassEmpty,
            contentDescription = "Wartet",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        RefreshItemState.Checking -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        is RefreshItemState.Done -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Fertig",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        is RefreshItemState.SkippedItem -> Icon(
            Icons.Filled.Info,
            contentDescription = "Übersprungen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        is RefreshItemState.Error -> Icon(
            Icons.Filled.Error,
            contentDescription = "Fehler",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
    }
}
