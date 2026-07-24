package de.versandapp.ui.log

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.versandapp.data.log.DiagnosticsLog
import de.versandapp.data.log.MailEntry
import java.text.DateFormat
import java.util.Date

/**
 * Diagnose-Protokoll mit zwei Reitern:
 *  - „Abfragen": pro Statusabfrage, welche Quelle was geliefert hat (inkl.
 *    echtem Fehlergrund).
 *  - „Mails": die beim Postfach-Import gescannten Mails – jede lässt sich in
 *    Gmail öffnen, z. B. um einen Abmelde-Link anzuklicken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val entries by DiagnosticsLog.entries.collectAsState()
    val mails by DiagnosticsLog.mails.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Protokoll") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (tab == 0) DiagnosticsLog.clear() else DiagnosticsLog.clearMails()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Leeren")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Abfragen") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Mails") })
            }
            if (tab == 0) QueryList(entries) else MailList(mails)
        }
    }
}

@Composable
private fun QueryList(entries: List<de.versandapp.data.log.LogEntry>) {
    if (entries.isEmpty()) {
        EmptyHint(
            "Noch keine Einträge. Drücke ↻ bei einem Paket – jede Quelle " +
                "und ihr Ergebnis wird hier festgehalten.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${entry.source} · ${entry.parcel}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                            .format(Date(entry.time)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MailList(mails: List<MailEntry>) {
    val context = LocalContext.current
    if (mails.isEmpty()) {
        EmptyHint(
            "Noch keine gescannten Mails. Nach einem Postfach-Import stehen hier " +
                "die gelesenen Mails – zum Öffnen in Gmail (z. B. zum Abmelden).",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(mails) { mail ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            mail.subject.ifBlank { "(kein Betreff)" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            mail.from,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                                .format(Date(mail.time)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(mail.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "In Gmail öffnen",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
