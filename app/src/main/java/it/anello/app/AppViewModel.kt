package it.anello.app

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.RingBle
import it.anello.app.ble.RingDataBus
import it.anello.app.data.DailyStore
import it.anello.app.data.DiaryStore
import it.anello.app.training.Activity
import it.anello.app.training.Fit
import it.anello.app.training.Training
import it.anello.app.training.TrainingStore
import it.anello.app.data.DataSource
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
    /** Diario: etichette per giorno (solo i giorni compilati). */
    val diary: Map<LocalDate, Set<String>> = emptyMap(),
    /** Allenamenti importati da file FIT. */
    val activities: List<Activity> = emptyList(),
    val importMessage: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = HealthRepository(app)
    private val store = DailyStore(app)
    private val ringStore = DailyStore(app, RingBle.RING_DAYS_FILE)
    private val settingsStore = SettingsStore(app)
    private val diaryStore = DiaryStore(app)
    private val trainingStore = TrainingStore(app)

    /** Archivio della fonte scelta: Health Connect (app Oura) o anello via Bluetooth. */
    private fun activeStore() =
        if (_state.value.settings.source == DataSource.RING) ringStore else store

    private fun reloadDays() = viewModelScope.launch {
        val days = withContext(Dispatchers.IO) { activeStore().load() }
        _state.update { it.copy(days = days) }
        // Con l'anello diretto i passi arrivano da Health Connect (telefono, app di fitness).
        if (_state.value.settings.source == DataSource.RING && _state.value.hcStatus == HcStatus.READY) {
            val merged = runCatching {
                val activity = repo.fetchActivity(30)
                withContext(Dispatchers.IO) { ringStore.upsertActivity(activity); ringStore.load() }
            }.getOrNull()
            if (merged != null && _state.value.settings.source == DataSource.RING) _state.update { it.copy(days = merged) }
        }
    }

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val (diary, acts) = withContext(Dispatchers.IO) { diaryStore.load() to trainingStore.load() }
            _state.update { it.copy(diary = diary, activities = acts) }
        }
        reloadDays()
        // Nuove notti dall'anello (sync manuale o automatica): aggiorna le schermate.
        viewModelScope.launch {
            RingDataBus.updated.collect {
                if (_state.value.settings.source == DataSource.RING) reloadDays()
            }
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
        // Con l'anello diretto i dati arrivano dalla scheda Anello / sync automatica.
        if (_state.value.settings.source == DataSource.RING) { reloadDays(); return }
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
        if (_state.value.settings.source == DataSource.RING) { reloadDays(); return }
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
        val sourceChanged = s.source != _state.value.settings.source
        settingsStore.save(s)
        _state.update { it.copy(settings = s) }
        if (sourceChanged) {
            _state.update { it.copy(days = emptyMap()) }
            reloadDays()
            if (s.source == DataSource.HEALTH_CONNECT) refreshStatus(syncIfReady = true)
        }
    }

    /** Conferma un nuovo livello normale dell'HRV da [date] per la fonte attuale. */
    fun confirmRebaseline(date: LocalDate) {
        val s = _state.value.settings
        val d = date.toString()
        updateSettings(
            if (s.source == DataSource.RING) s.copy(rebaselineRing = s.rebaselineRing + d)
            else s.copy(rebaselineHc = s.rebaselineHc + d)
        )
    }

    /** Imposta le etichette del diario per [date]; null cancella la voce (giorno non compilato). */
    fun setDiary(date: LocalDate, tags: Set<String>?) {
        _state.update { st ->
            st.copy(diary = if (tags == null) st.diary - date else st.diary + (date to tags))
        }
        viewModelScope.launch(Dispatchers.IO) { diaryStore.set(date, tags) }
    }

    /** Importa file FIT (contenuto in byte). Lo stesso file importato due volte non si duplica. */
    fun importFit(files: List<ByteArray>) {
        val s = _state.value.settings
        if (s.cpW <= 0 || s.wPrimeJ <= 0) {
            _state.update { it.copy(importMessage = "Imposta prima CP e W′.") }
            return
        }
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                var added = 0; var dup = 0
                val errors = ArrayList<String>()
                for (bytes in files) {
                    try {
                        val a = Training.summarize(bytes, s.cpW.toDouble(), s.wPrimeJ.toDouble())
                        if (trainingStore.add(a)) added++ else dup++
                    } catch (e: Fit.FitException) {
                        errors += e.message ?: "file non valido"
                    } catch (e: IllegalArgumentException) {
                        errors += e.message ?: "valori non validi"
                    }
                }
                buildString {
                    append("$added importati")
                    if (dup > 0) append(", $dup già presenti")
                    if (errors.isNotEmpty()) append(", ${errors.size} non letti (${errors.first()})")
                }
            }
            val acts = withContext(Dispatchers.IO) { trainingStore.load() }
            _state.update { it.copy(activities = acts, importMessage = msg) }
        }
    }

    fun removeActivity(id: String) = viewModelScope.launch {
        val acts = withContext(Dispatchers.IO) { trainingStore.remove(id); trainingStore.load() }
        _state.update { it.copy(activities = acts) }
    }

    fun exportCsv(): String = activeStore().toCsv()
}
