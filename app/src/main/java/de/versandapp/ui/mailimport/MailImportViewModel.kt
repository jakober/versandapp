package de.versandapp.ui.mailimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.versandapp.VersandApp
import de.versandapp.data.mail.ShipmentMailScanner
import de.versandapp.data.settings.SettingsRepository
import de.versandapp.data.tracking.TrackingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SuggestionItem(
    val suggestion: ShipmentSuggestion,
    val selected: Boolean = true,
)

sealed interface ImportUiState {
    data object NotConnected : ImportUiState
    data object Scanning : ImportUiState
    data class Ready(val items: List<SuggestionItem>) : ImportUiState
    data class Error(val message: String) : ImportUiState
    data object Done : ImportUiState
}

class MailImportViewModel(
    private val repository: TrackingRepository,
    private val settings: SettingsRepository,
    private val scanner: ShipmentMailScanner = ShipmentMailScanner(),
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.NotConnected)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** true, wenn Gmail schon einmal verknüpft wurde – dann verbindet der Screen automatisch. */
    fun isGmailLinked(): Boolean = settings.gmailLinked

    /** Wird nach erfolgreicher Gmail-Autorisierung mit dem Access-Token aufgerufen. */
    fun scan(accessToken: String) {
        settings.gmailLinked = true // Verknüpfung dauerhaft merken
        _state.value = ImportUiState.Scanning
        viewModelScope.launch {
            try {
                val known = repository.trackedNumbers()
                val suggestions = scanner.scan(accessToken, settings.anthropicApiKey)
                    .filter { it.trackingNumber !in known }
                _state.value = ImportUiState.Ready(suggestions.map { SuggestionItem(it) })
            } catch (e: Exception) {
                _state.value = ImportUiState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    fun onAuthError(e: Exception) {
        _state.value = ImportUiState.Error(
            "Gmail-Anmeldung fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}. " +
                "Ist der OAuth-Client in der Google Cloud Console eingerichtet (siehe README)?"
        )
    }

    fun toggle(trackingNumber: String) {
        val current = _state.value as? ImportUiState.Ready ?: return
        _state.value = ImportUiState.Ready(
            current.items.map {
                if (it.suggestion.trackingNumber == trackingNumber) it.copy(selected = !it.selected)
                else it
            }
        )
    }

    fun importSelected() {
        val current = _state.value as? ImportUiState.Ready ?: return
        viewModelScope.launch {
            current.items.filter { it.selected }.forEach { item ->
                repository.addParcel(
                    trackingNumber = item.suggestion.trackingNumber,
                    carrier = item.suggestion.carrier,
                    label = item.suggestion.sourceSubject.takeIf { it.isNotBlank() },
                )
            }
            _state.value = ImportUiState.Done
        }
    }

    fun reset() {
        _state.value = ImportUiState.NotConnected
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as VersandApp
                MailImportViewModel(app.repository, app.settings)
            }
        }
    }
}
