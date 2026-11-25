package it.anello.app

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.data.DailyStore
import it.anello.app.data.DailySummary
import it.anello.app.data.HealthRepository
import it.anello.app.data.SettingsStore
import it.anello.app.data.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class HcStatus { CHECKING, NOT_INSTALLED, NEEDS_UPDATE, NO_PERMISSIONS, READY }

data class UiState(
    val hcStatus: HcStatus = HcStatus.CHECKING,
    val syncing: Boolean = false,
    val lastError: String? = null,
    val days: Map<LocalDate, DailySummary> = emptyMap(),
    val settings: UserSettings = UserSettings(),
    val sources: Map<String, Int>? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = HealthRepository(app)
    private val store = DailyStore(app)
    private val settingsStore = SettingsStore(app)

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val days = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(days = days) }
        }
    }

    fun refreshStatus(syncIfReady: Boolean = false) = viewModelScope.launch {
        val status = when (repo.sdkStatus()) {
            HealthConnectClient.SDK_UNAVAILABLE -> HcStatus.NOT_INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.NEEDS_UPDATE
            else -> {
                val granted = runCatching { repo.grantedPermissions() }.getOrDefault(emptySet())
                if (granted.containsAll(repo.permissions)) HcStatus.READY else HcStatus.NO_PERMISSIONS
            }
        }
        _state.update { it.copy(hcStatus = status) }
        if (status == HcStatus.READY && syncIfReady) sync()
    }

    fun sync() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, lastError = null) }
            try {
                // Al primo avvio scarichiamo tutto quello che Health Connect espone (30 giorni),
                // poi solo gli ultimi giorni, che possono ancora cambiare.
                val n = if (_state.value.days.isEmpty()) 30 else 4
                val fresh = repo.fetchDays(n, _state.value.settings.onlyOura)
                val all = withContext(Dispatchers.IO) {
                    store.upsert(fresh)
                    store.load()
                }
                _state.update { it.copy(days = all) }
            } catch (e: Exception) {
                _state.update { it.copy(lastError = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    /** Riscarica tutti i 30 giorni disponibili (utile dopo aver cambiato le impostazioni). */
    fun fullResync() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, lastError = null) }
            try {
                val fresh = repo.fetchDays(30, _state.value.settings.onlyOura)
                val all = withContext(Dispatchers.IO) { store.upsert(fresh); store.load() }
                _state.update { it.copy(days = all) }
            } catch (e: Exception) {
                _state.update { it.copy(lastError = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    fun loadSources() = viewModelScope.launch {
        val src = runCatching { repo.sleepSources() }.getOrElse { mapOf("errore: ${it.message}" to 0) }
        _state.update { it.copy(sources = src) }
    }

    fun updateSettings(s: UserSettings) {
        settingsStore.save(s)
        _state.update { it.copy(settings = s) }
    }

    fun exportCsv(): String = store.toCsv()
}
