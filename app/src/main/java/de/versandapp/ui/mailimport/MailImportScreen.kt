package de.versandapp.ui.mailimport

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import de.versandapp.data.mail.GmailService
import de.versandapp.ui.components.CarrierBadge

/**
 * Schritt 2: Postfach verknüpfen und gefundene Sendungen importieren.
 *
 * Ablauf: Google-Autorisierung (nur Lese-Scope) → Versand-Mails der letzten
 * 60 Tage durchsuchen → gefundene Trackingnummern als Vorschlagsliste
 * anzeigen → Nutzer wählt aus, was importiert wird.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailImportScreen(
    viewModel: MailImportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val authClient = remember { Identity.getAuthorizationClient(context) }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching {
                authClient.getAuthorizationResultFromIntent(result.data)
            }.onSuccess { authResult ->
                authResult.accessToken?.let { viewModel.scan(it) }
            }.onFailure { viewModel.onAuthError(Exception(it)) }
        }
    }

    fun connectGmail() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GmailService.SCOPE_READONLY)))
            .build()
        authClient.authorize(request)
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                when {
                    result.hasResolution() && pendingIntent != null -> authLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                    result.accessToken != null -> viewModel.scan(result.accessToken!!)
                    else -> viewModel.onAuthError(Exception("Kein Access-Token erhalten"))
                }
            }
            .addOnFailureListener { viewModel.onAuthError(Exception(it)) }
    }

    // Bereits verknüpft? Dann still verbinden und direkt scannen –
    // der Google-Dialog erscheint nur beim allerersten Mal.
    LaunchedEffect(Unit) {
        if (viewModel.isGmailLinked() && state is ImportUiState.NotConnected) {
            connectGmail()
        }
    }

    LaunchedEffect(state) {
        if (state is ImportUiState.Done) {
            viewModel.reset()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aus Postfach importieren") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            ImportUiState.NotConnected -> ConnectPrompt(
                modifier = Modifier.padding(innerPadding),
                onConnect = ::connectGmail,
            )
            ImportUiState.Scanning -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Postfach wird durchsucht …")
                }
            }
            is ImportUiState.Error -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = ::connectGmail) { Text("Erneut versuchen") }
                }
            }
            is ImportUiState.Ready -> SuggestionList(
                modifier = Modifier.padding(innerPadding),
                items = current.items,
                onToggle = viewModel::toggle,
                onImport = viewModel::importSelected,
            )
            ImportUiState.Done -> Unit
        }
    }
}

@Composable
private fun ConnectPrompt(modifier: Modifier = Modifier, onConnect: () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.MarkEmailRead,
                contentDescription = null,
                modifier = Modifier.width(64.dp).height(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("Postfach verknüpfen", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "VersandApp durchsucht deine Versand-Mails der letzten 60 Tage " +
                    "nach Trackingnummern – nur mit Lesezugriff und komplett auf " +
                    "deinem Gerät. Nichts wird importiert, ohne dass du es bestätigst.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onConnect) { Text("Mit Gmail verbinden") }
        }
    }
}

@Composable
private fun SuggestionList(
    modifier: Modifier = Modifier,
    items: List<SuggestionItem>,
    onToggle: (String) -> Unit,
    onImport: () -> Unit,
) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Keine neuen Sendungen zum Hinzufügen. Bereits verfolgte Pakete werden " +
                    "nicht doppelt angelegt – ihr Status wird aber aus neuen Mails " +
                    "aktualisiert (z. B. Amazon „in Zustellung").",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val selectedCount = items.count { it.selected }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.suggestion.trackingNumber }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.selected,
                            onCheckedChange = { onToggle(item.suggestion.trackingNumber) },
                        )
                        CarrierBadge(carrier = item.suggestion.carrier, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                item.suggestion.trackingNumber,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.suggestion.sourceSubject,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Button(
            onClick = onImport,
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                if (selectedCount == 1) "1 Sendung importieren"
                else "$selectedCount Sendungen importieren"
            )
        }
    }
}
