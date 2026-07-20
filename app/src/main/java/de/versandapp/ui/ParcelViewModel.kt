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
import de.versandapp.data.tracking.TrackingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ParcelViewModel(
    private val repository: TrackingRepository,
) : ViewModel() {

    val parcels: StateFlow<List<ParcelWithEvents>> = repository.observeParcels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refreshAll()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh(parcelId: Long) {
        viewModelScope.launch {
            runCatching { repository.refresh(parcelId) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as VersandApp
                ParcelViewModel(app.repository)
            }
        }
    }
}
