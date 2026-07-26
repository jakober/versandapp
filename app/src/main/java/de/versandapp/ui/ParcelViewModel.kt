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
import de.versandapp.data.model.ParcelStatus
import de.versandapp.data.mail.MailSync
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.data.settings.AppSettings
import de.versandapp.data.settings.SettingsRepository
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

    /**
     * Pakete, die gerade einzeln aktualisiert werden, samt aktuellem Schritt-Text
     * (z. B. „ChatGPT recherchiert online …") – für Spinner + Schrittanzeige auf
     * Karte und Detailseite. Enthaltene ID = lädt gerade.
     */
    private val _refreshSteps = MutableStateFlow<Map<Long, String>>(emptyMap())
    val refreshSteps: StateFlow<Map<Long, String>> = _refreshSteps.asStateFlow()

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

    /** Ändert Name (Label), Carrier und Status eines Pakets. */
    fun updateParcel(parcel: Parcel, label: String?, carrier: Carrier, status: ParcelStatus) {
        viewModelScope.launch {
            repository.updateParcel(parcel, label, carrier, status)
        }
    }

    /**
     * „↻"/Pull-to-Refresh: das Postfach neu durchsuchen (reine Postfach-App, kein
     * Online-Tracking mehr). Schaut mindestens die letzten 7 Tage zurück, legt neue
     * Sendungen an und zieht den Status bekannter Sendungen aus den Mails nach.
     */
    fun refreshAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                MailSync.sync(app, mailLookbackEpochSeconds())
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Fenster ab dem mind. die letzten 7 Tage durchsucht werden (auch nach jüngstem Scan). */
    private fun mailLookbackEpochSeconds(): Long {
        val now = System.currentTimeMillis() / 1000
        val sevenDaysAgo = now - 7L * 24 * 3600
        val last = settings.lastMailImportEpochSeconds
        return if (last > 0L) minOf(last, sevenDaysAgo) else sevenDaysAgo
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

    /**
     * Einzel-Aktualisierung (Wischgeste/Detail-Button): prüft ebenfalls das
     * Postfach (kein Online-Tracking mehr) und zeigt am Paket einen Spinner.
     */
    fun refresh(parcelId: Long) {
        if (parcelId in _refreshSteps.value) return
        viewModelScope.launch {
            _refreshSteps.value = _refreshSteps.value + (parcelId to "Postfach wird geprüft …")
            try {
                MailSync.sync(app, mailLookbackEpochSeconds())
            } finally {
                _refreshSteps.value = _refreshSteps.value - parcelId
            }
        }
    }

    fun currentSettings(): AppSettings = settings.current()

    fun notifyAlways(): Boolean = settings.notifyAlways

    /** true = immer benachrichtigen, false = nur bei Neuigkeiten. */
    fun setNotifyAlways(enabled: Boolean) {
        settings.notifyAlways = enabled
    }

    fun archiveAfterDays(): Int = settings.archiveAfterDays

    fun setArchiveAfterDays(days: Int) {
        settings.archiveAfterDays = days
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
