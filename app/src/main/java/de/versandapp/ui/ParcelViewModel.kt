package de.versandapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.versandapp.VersandApp
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.Parcel
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.data.settings.AppSettings
import de.versandapp.data.settings.SettingsRepository
import de.versandapp.data.tracking.RefreshOutcome
import de.versandapp.data.tracking.TrackingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Zustand einer einzelnen Sendung in der Live-Fortschrittsanzeige. */
sealed interface RefreshItemState {
    data object Waiting : RefreshItemState
    data class Checking(val text: String) : RefreshItemState
    data class Done(val text: String) : RefreshItemState
    data class SkippedItem(val text: String) : RefreshItemState
    data class Error(val text: String) : RefreshItemState
}

data class RefreshProgressItem(
    val parcelId: Long,
    val title: String,
    val carrier: Carrier,
    val state: RefreshItemState,
)

data class RefreshProgress(
    val items: List<RefreshProgressItem>,
    val finished: Boolean,
)

class ParcelViewModel(
    private val repository: TrackingRepository,
    private val settings: SettingsRepository,
    private val app: VersandApp,
) : ViewModel() {

    val parcels: StateFlow<List<ParcelWithEvents>> = repository.observeParcels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Live-Fortschritt der manuellen Online-Prüfung; null = kein Fenster sichtbar. */
    private val _refreshProgress = MutableStateFlow<RefreshProgress?>(null)
    val refreshProgress: StateFlow<RefreshProgress?> = _refreshProgress.asStateFlow()

    /** true, wenn der Nutzer das Fenster während eines laufenden Durchgangs geschlossen hat. */
    private var progressDismissed = false

    fun observeParcel(id: Long): Flow<ParcelWithEvents?> = repository.observeParcel(id)

    fun addParcel(trackingNumber: String, carrier: Carrier, label: String?) {
        viewModelScope.launch {
            repository.addParcel(trackingNumber, carrier, label)
        }
    }

    fun deleteParcel(parcel: Parcel) {
        viewModelScope.launch {
            repository.deleteParcel(parcel)
        }
    }

    /**
     * Manuelle Online-Prüfung aller offenen Sendungen mit Live-Fortschritt:
     * Sendungen werden nacheinander geprüft, der Dialog zeigt jeweils, welche
     * gerade dran ist und was dabei herauskam (auch "nichts gefunden").
     */
    fun refreshAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            progressDismissed = false
            try {
                val parcels = repository.openParcels()
                var items = parcels.map { parcel ->
                    RefreshProgressItem(
                        parcelId = parcel.id,
                        title = parcel.label ?: parcel.trackingNumber,
                        carrier = parcel.carrier,
                        state = RefreshItemState.Waiting,
                    )
                }
                publishProgress(RefreshProgress(items, finished = parcels.isEmpty()))

                parcels.forEachIndexed { index, parcel ->
                    items = items.replaceAt(index) {
                        it.copy(state = RefreshItemState.Checking("Wird geprüft …"))
                    }
                    publishProgress(RefreshProgress(items, finished = false))

                    val outcome = repository.refresh(
                        parcel.id,
                        force = true,
                        onProgress = { label ->
                            items = items.replaceAt(index) {
                                it.copy(state = RefreshItemState.Checking(label))
                            }
                            publishProgress(RefreshProgress(items, finished = false))
                        },
                    )
                    val state = when (outcome) {
                        is RefreshOutcome.Updated -> RefreshItemState.Done(
                            "${outcome.status.displayName} · ${outcome.eventCount} Ereignisse"
                        )
                        is RefreshOutcome.Skipped -> RefreshItemState.SkippedItem(outcome.reason)
                        is RefreshOutcome.Failed -> RefreshItemState.Error(outcome.message)
                    }
                    items = items.replaceAt(index) { it.copy(state = state) }
                    publishProgress(RefreshProgress(items, finished = false))
                }
                publishProgress(RefreshProgress(items, finished = true))
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun dismissRefreshProgress() {
        progressDismissed = true
        _refreshProgress.value = null
    }

    private fun publishProgress(progress: RefreshProgress) {
        if (!progressDismissed) _refreshProgress.value = progress
    }

    private fun List<RefreshProgressItem>.replaceAt(
        index: Int,
        transform: (RefreshProgressItem) -> RefreshProgressItem,
    ): List<RefreshProgressItem> =
        mapIndexed { i, item -> if (i == index) transform(item) else item }

    fun refresh(parcelId: Long) {
        viewModelScope.launch {
            runCatching { repository.refresh(parcelId, force = true) }
        }
    }

    fun currentSettings(): AppSettings = settings.current()

    fun saveSettings(
        anthropicApiKey: String,
        dhlApiKey: String,
        ship24ApiKey: String,
        openAiApiKey: String,
        openAiTrackingModel: String,
    ) {
        settings.anthropicApiKey = anthropicApiKey
        settings.dhlApiKey = dhlApiKey
        settings.ship24ApiKey = ship24ApiKey
        settings.openAiApiKey = openAiApiKey
        settings.openAiTrackingModel = openAiTrackingModel
    }

    fun notifyAlways(): Boolean = settings.notifyAlways

    /** true = immer benachrichtigen, false = nur bei Neuigkeiten. */
    fun setNotifyAlways(enabled: Boolean) {
        settings.notifyAlways = enabled
    }

    fun testPushEnabled(): Boolean = settings.testPushEnabled

    /** Schaltet den 2-Minuten-Test-Push an/aus (wirkt sofort). */
    fun setTestPush(enabled: Boolean) = app.setTestPush(enabled)

    /** Feuert sofort eine einzelne Test-Push zum Prüfen der Pipeline. */
    fun sendTestNotification() = app.sendTestNotificationNow()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as VersandApp
                ParcelViewModel(app.repository, app.settings, app)
            }
        }
    }
}
