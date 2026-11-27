package it.anello.app

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.RingDataBus
import it.anello.app.ble.RingStore
import it.anello.app.data.DailySummary
import it.anello.app.data.DataSource
import it.anello.app.data.DiaryStore
import it.anello.app.data.HealthRepository
import it.anello.app.data.SettingsStore
import it.anello.app.data.Sources
import it.anello.app.data.UserSettings
import it.anello.app.score.Calibration
import it.anello.app.score.Experiment
import it.anello.app.data.ExperimentStore
import it.anello.app.training.Activity
import it.anello.app.training.Fit
import it.anello.app.training.Training
import it.anello.app.training.TrainingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class HcStatus { CHECKING, NOT_INSTALLED, NEEDS_UPDATE, NO_PERMISSIONS, READY }

/** Una fonte disponibile: identificativo (vedi Sources) e nome leggibile. */
data class SourceInfo(val id: String, val label: String)

data class UiState(
    val hcStatus: HcStatus = HcStatus.CHECKING,
    val syncing: Boolean = false,
    val lastError: String? = null,
    /** Notti della fonte attiva, comprese quelle riempite da un'altra fonte calibrata. */
    val days: Map<LocalDate, DailySummary> = emptyMap(),
    val settings: UserSettings = UserSettings(),
    /** App trovate in Health Connect con il numero di notti (dopo "Cerca fonti"). */
    val sources: Map<String, Int>? = null,
    /** Tutte le fonti selezionabili. */
    val available: List<SourceInfo> = emptyList(),
    /** Notti delle altre fonti, per confronto e calibrazione. */
    val others: Map<String, Map<LocalDate, DailySummary>> = emptyMap(),
    /** Calibrazione di ogni altra fonte rispetto a quella attiva. */
    val calibrations: Map<String, Calibration.Result> = emptyMap(),
    /** Diario: etichette per giorno (solo i giorni compilati). */
    val diary: Map<LocalDate, Set<String>> = emptyMap(),
    /** Allenamenti importati da file FIT. */
    val activities: List<Activity> = emptyList(),
    val importMessage: String? = null,
    /** Esperimenti personali a fasi alterne. */
    val experiments: List<Experiment> = emptyList(),
) {
    fun label(id: String): String = available.firstOrNull { it.id == id }?.label ?: id
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = HealthRepository(app)
    private val settingsStore = SettingsStore(app)
    private val diaryStore = DiaryStore(app)
    private val trainingStore = TrainingStore(app)
    private val experimentStore = ExperimentStore(app)

    private fun store(id: String) = Sources.store(getApplication(), id)

    private fun availableSources(s: UserSettings): List<SourceInfo> {
        val ids = s.hcPackages.sorted().map { Sources.hc(it) } +
            RingStore(getApplication()).pairedRings().map { Sources.ring(it) }
        return (ids + s.sourceId).distinct().map { SourceInfo(it, Sources.label(getApplication(), it)) }
    }

    /**
     * Carica le notti della fonte attiva e delle altre, calcola le calibrazioni e, se richiesto,
     * riempie le notti mancanti della fonte attiva con quelle (convertite) di un'altra fonte.
     */
    private fun reloadDays(): Job = viewModelScope.launch {
        val s = _state.value.settings
        val available = availableSources(s)
        val all = withContext(Dispatchers.IO) { available.associate { it.id to store(it.id).load() } }
        val active = all[s.sourceId] ?: emptyMap()
        val others = all - s.sourceId
        val calibrations = others.mapValues { (_, days) -> Calibration.calibrate(active, days) }
        val fill = s.fillFrom?.takeIf { it in others }
        val days = if (fill != null) {
            Calibration.fill(active, others.getValue(fill), calibrations.getValue(fill),
                available.first { it.id == fill }.label)
        } else active
        _state.update { it.copy(days = days, available = available, others = others, calibrations = calibrations) }

        // Con un anello diretto i passi arrivano da Health Connect (telefono, app di fitness).
        if (s.source == DataSource.RING && _state.value.hcStatus == HcStatus.READY && !activityMerged) {
            activityMerged = true
            runCatching {
                val activity = repo.fetchActivity(30)
                withContext(Dispatchers.IO) { store(s.sourceId).upsertActivity(activity) }
            }.onSuccess { reloadDays() }
        } else {
            activityMerged = false
        }
    }

    /** Evita di rileggere l'attività in ciclo: una lettura per ogni ricarica richiesta. */
    private var activityMerged = false

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val (diary, acts) = withContext(Dispatchers.IO) { diaryStore.load() to trainingStore.load() }
            val exps = withContext(Dispatchers.IO) { experimentStore.load() }
            _state.update { it.copy(diary = diary, activities = acts, experiments = exps) }
        }
        reloadDays()
        // Nuove notti da un anello (sync manuale o automatica) o nuovo anello associato.
        viewModelScope.launch { RingDataBus.updated.collect { reloadDays() } }
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

    /** Aggiorna da Health Connect tutte le app seguite, poi ricarica. */
    fun sync(fullDays: Boolean = false) {
        if (_state.value.hcStatus != HcStatus.READY) { reloadDays(); return }
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, lastError = null) }
            try {
                for (pkg in _state.value.settings.hcPackages) {
                    val st = store(Sources.hc(pkg))
                    // Primo scarico: tutto quello che Health Connect espone (30 giorni);
                    // poi solo gli ultimi giorni, che possono ancora cambiare.
                    val empty = withContext(Dispatchers.IO) { st.load().isEmpty() }
                    val fresh = repo.fetchDays(if (fullDays || empty) 30 else 4, pkg)
                    withContext(Dispatchers.IO) { st.upsert(fresh.filter { it.hasSleep || it.steps != null || it.activeKcal != null }) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(lastError = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(syncing = false) }
            }
            reloadDays()
        }
    }

    /** Riscarica tutti i 30 giorni disponibili. */
    fun fullResync() = sync(fullDays = true)

    /** Cerca in Health Connect le app che scrivono il sonno e le aggiunge alle fonti. */
    fun loadSources() = viewModelScope.launch {
        val src = runCatching { repo.sleepSources() }.getOrElse { mapOf("errore: ${it.message}" to 0) }
        _state.update { it.copy(sources = src) }
        val found = src.filter { it.value > 0 }.keys
        val s = _state.value.settings
        if (!s.hcPackages.containsAll(found)) {
            updateSettings(s.copy(hcPackages = s.hcPackages + found))
            sync()
        }
    }

    fun updateSettings(s: UserSettings) {
        val old = _state.value.settings
        settingsStore.save(s)
        _state.update { it.copy(settings = s) }
        if (s.sourceId != old.sourceId || s.fillFrom != old.fillFrom || s.hcPackages != old.hcPackages) {
            if (s.sourceId != old.sourceId) _state.update { it.copy(days = emptyMap()) }
            reloadDays()
            if (s.sourceId != old.sourceId && s.source == DataSource.HEALTH_CONNECT) refreshStatus(syncIfReady = true)
        }
    }

    /** Conferma un nuovo livello normale dell'HRV da [date] per la fonte attuale. */
    fun confirmRebaseline(date: LocalDate) {
        val s = _state.value.settings
        updateSettings(s.copy(rebaseline = s.rebaseline + "${s.sourceId}|$date"))
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

    /** Avvia oggi un esperimento: fase A (con la modifica), poi fase B (senza), per [cycles] volte. */
    fun startExperiment(name: String, phaseDays: Int, cycles: Int) {
        val e = Experiment(System.currentTimeMillis().toString(), name.trim().ifEmpty { "Esperimento" }, LocalDate.now(), phaseDays, cycles)
        val all = _state.value.experiments + e
        _state.update { it.copy(experiments = all) }
        viewModelScope.launch(Dispatchers.IO) { experimentStore.save(all) }
    }

    fun removeExperiment(id: String) {
        val all = _state.value.experiments.filterNot { it.id == id }
        _state.update { it.copy(experiments = all) }
        viewModelScope.launch(Dispatchers.IO) { experimentStore.save(all) }
    }

    fun exportCsv(): String = store(_state.value.settings.sourceId).toCsv()
}
