package de.versandapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import de.versandapp.ui.ParcelViewModel

/**
 * Eigener Einstellungs-Screen (ersetzt den früheren langen Dialog):
 * API-Keys, Benachrichtigungen, Test-Push und Archiv-Regel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ParcelViewModel,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val initial = remember { viewModel.currentSettings() }
    var anthropicKey by remember { mutableStateOf(initial.anthropicApiKey) }
    var dhlKey by remember { mutableStateOf(initial.dhlApiKey) }
    var ship24Key by remember { mutableStateOf(initial.ship24ApiKey) }
    var easyPostKey by remember { mutableStateOf(initial.easyPostApiKey) }
    var openAiKey by remember { mutableStateOf(initial.openAiApiKey) }
    var openAiModel by remember { mutableStateOf(initial.openAiTrackingModel) }
    var notifyAlways by remember { mutableStateOf(viewModel.notifyAlways()) }
    var testPush by remember { mutableStateOf(viewModel.testPushEnabled()) }
    var archiveDays by remember { mutableStateOf(viewModel.archiveAfterDays().toString()) }

    fun persist() {
        viewModel.saveSettings(anthropicKey, dhlKey, ship24Key, easyPostKey, openAiKey, openAiModel)
        viewModel.setArchiveAfterDays(archiveDays.toIntOrNull() ?: 14)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = { persist(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Tracking-Quellen", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            KeyField("DHL API-Key", dhlKey, { dhlKey = it },
                "Kostenlos von developer.dhl.com – DHL/Post über die offizielle API.")
            KeyField("EasyPost API-Key", easyPostKey, { easyPostKey = it },
                "Production-Key von easypost.com – günstige Tracking-API (~1-2 EUR/Monat, kein Monatsminimum).")
            KeyField("Ship24 API-Key", ship24Key, { ship24Key = it },
                "ship24.com – zuverlässige API für alle Dienste (bezahlt).")
            KeyField("OpenAI API-Key (ChatGPT)", openAiKey, { openAiKey = it },
                "platform.openai.com – zusätzliche Online-Suche per ChatGPT.")
            KeyField("OpenAI-Modell (Sendungssuche)", openAiModel, { openAiModel = it },
                "Nur ändern, wenn der vorgegebene Name im OpenAI-Konto nicht existiert.")
            KeyField("Anthropic API-Key (Claude)", anthropicKey, { anthropicKey = it },
                "console.anthropic.com – für Mail-Erkennung und Online-Suche per Claude.")

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Benachrichtigungen", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Immer benachrichtigen", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Nach jedem Lauf melden (auch ohne Neuigkeiten). Nachts 22-6 Uhr ist Ruhe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = notifyAlways, onCheckedChange = {
                    notifyAlways = it; viewModel.setNotifyAlways(it)
                })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { viewModel.sendTestNotification() }, modifier = Modifier.fillMaxWidth()) {
                Text("Jetzt Test-Push senden")
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Test-Push alle 2 Minuten", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Prüft die Hintergrund-Zustellung. Danach wieder ausschalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = testPush, onCheckedChange = {
                    testPush = it; viewModel.setTestPush(it)
                })
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Archiv", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = archiveDays,
                onValueChange = { archiveDays = it.filter { c -> c.isDigit() } },
                label = { Text("Zugestellte archivieren nach (Tagen)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Diagnose", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { persist(); onOpenLog() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Protokoll anzeigen")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Zeigt für jede Abfrage, welche Quelle was geliefert hat – inkl. " +
                    "echtem Fehlergrund (z. B. warum DHL oder ChatGPT nichts fand).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "Änderungen werden beim Zurückgehen gespeichert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
}
