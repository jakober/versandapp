package de.versandapp.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.versandapp.data.model.ParcelStatus
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.ui.ParcelViewModel
import de.versandapp.ui.components.CarrierBadge
import de.versandapp.ui.components.StatusChip
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelListScreen(
    viewModel: ParcelViewModel,
    onParcelClick: (Long) -> Unit,
    onImportClick: () -> Unit,
) {
    val parcels by viewModel.parcels.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshSteps by viewModel.refreshSteps.collectAsState()
    val refreshProgress by viewModel.refreshProgress.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Pull-to-Refresh: nach unten ziehen löst dieselbe Aktualisierung aus wie
    // das ↻-Icon. Beim Erreichen der Auslöse-Schwelle vibriert das Gerät kurz.
    val pullState = rememberPullToRefreshState()
    val haptics = LocalHapticFeedback.current
    var passedThreshold by remember { mutableStateOf(false) }
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction }.collect { fraction ->
            if (fraction >= 1f && !passedThreshold) {
                passedThreshold = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            } else if (fraction < 1f && passedThreshold) {
                passedThreshold = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meine Pakete") },
                actions = {
                    IconButton(onClick = onImportClick) {
                        Icon(Icons.Filled.MarkEmailRead, contentDescription = "Aus Postfach importieren")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).width(24.dp).height(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshAll() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Alle aktualisieren")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Paket hinzufügen")
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            if (parcels.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                // Offene Sendungen (neueste zuerst) oben, zugestellte in einem
                // eigenen Abschnitt darunter.
                val (delivered, open) = parcels.partition {
                    it.parcel.status == ParcelStatus.DELIVERED
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(open, key = { it.parcel.id }) { item ->
                        SwipeableParcelCard(
                            item = item,
                            refreshStep = refreshSteps[item.parcel.id],
                            onRefresh = { viewModel.refresh(item.parcel.id) },
                            onClick = { onParcelClick(item.parcel.id) },
                        )
                    }
                    if (delivered.isNotEmpty()) {
                        item(key = "delivered_header") {
                            Text(
                                "Zugestellt",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(delivered, key = { it.parcel.id }) { item ->
                            SwipeableParcelCard(
                                item = item,
                                refreshStep = refreshSteps[item.parcel.id],
                                onRefresh = { viewModel.refresh(item.parcel.id) },
                                onClick = { onParcelClick(item.parcel.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddParcelDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { trackingNumber, carrier, label ->
                viewModel.addParcel(trackingNumber, carrier, label)
                showAddDialog = false
            },
        )
    }

    refreshProgress?.let { progress ->
        RefreshProgressDialog(
            progress = progress,
            onDismiss = { viewModel.dismissRefreshProgress() },
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            initial = viewModel.currentSettings(),
            testPushInitial = viewModel.testPushEnabled(),
            notifyAlwaysInitial = viewModel.notifyAlways(),
            onDismiss = { showSettingsDialog = false },
            onTestNow = { viewModel.sendTestNotification() },
            onTestPushChange = { viewModel.setTestPush(it) },
            onNotifyAlwaysChange = { viewModel.setNotifyAlways(it) },
            onSave = { anthropicKey, dhlKey, ship24Key, openAiKey, openAiModel ->
                viewModel.saveSettings(anthropicKey, dhlKey, ship24Key, openAiKey, openAiModel)
                showSettingsDialog = false
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📦", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text("Noch keine Pakete", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Füge mit + deine erste Sendung hinzu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Paketkarte mit Wischgeste: nach rechts schieben zeigt links ein
 * Reload-Zeichen; bei Erreichen der Schwelle vibriert es und beim Loslassen
 * wird genau dieses Paket aktualisiert (die Karte schnappt zurück).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableParcelCard(
    item: ParcelWithEvents,
    refreshStep: String?,
    onRefresh: () -> Unit,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onRefresh()
            }
            false // nie wirklich „wegwischen" – immer zurückschnappen
        },
    )
    // Sicherheitsnetz: falls die Box doch mal nicht auf Settled steht, zurücksetzen.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(start = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Aktualisieren",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        ParcelCard(item = item, refreshStep = refreshStep, onClick = onClick)
    }
}

@Composable
private fun ParcelCard(
    item: ParcelWithEvents,
    refreshStep: String? = null,
    onClick: () -> Unit,
) {
    val parcel = item.parcel
    val latest = item.latestEvent
    val isRefreshing = refreshStep != null

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarrierBadge(carrier = parcel.carrier)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = parcel.label ?: parcel.trackingNumber,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (parcel.label != null) {
                    Text(
                        text = parcel.trackingNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val estimated = parcel.estimatedDelivery
                if (estimated != null && parcel.status != ParcelStatus.DELIVERED) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Zustellung vsl. " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(estimated)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (refreshStep != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = refreshStep,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (latest != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(latest.description)
                            latest.location?.let { append(" – $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT,
                        ).format(Date(latest.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                StatusChip(status = parcel.status)
            }
        }
    }
}
