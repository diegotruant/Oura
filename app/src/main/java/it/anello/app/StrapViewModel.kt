package it.anello.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.HrStrap
import it.anello.app.data.StrapMeasurement
import it.anello.app.data.StrapStore
import it.anello.app.score.Hrv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class StrapUiState(
    val scanning: Boolean = false,
    val devices: List<HrStrap.Found> = emptyList(),
    val address: String? = null,
    val name: String? = null,
    val measuring: Boolean = false,
    val status: String? = null,
    val secondsLeft: Int = 0,
    val liveHr: Int? = null,
    val beats: Int = 0,
    val last: StrapMeasurement? = null,
    val history: List<StrapMeasurement> = emptyList(),
)

/**
 * Misura dell'HRV con una fascia cardio: 30 secondi di assestamento scartati, poi
 * [MEASURE_SECONDS] secondi di intervalli RR, da cui si calcola l'RMSSD.
 */
class StrapViewModel(app: Application) : AndroidViewModel(app) {
    private val store = StrapStore(app)
    private val strap = HrStrap(app)
    private var job: Job? = null

    private val _state = MutableStateFlow(StrapUiState(address = store.address, name = store.name))
    val state: StateFlow<StrapUiState> = _state

    init {
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(history = h) }
        }
    }

    fun startScan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, devices = emptyList()) }
        strap.startScan { f ->
            _state.update { s -> s.copy(devices = (s.devices.filterNot { it.address == f.address } + f).sortedByDescending { it.rssi }) }
        }
    }

    fun stopScan() { strap.stopScan(); _state.update { it.copy(scanning = false) } }

    fun select(f: HrStrap.Found) {
        store.address = f.address; store.name = f.name
        _state.update { it.copy(address = f.address, name = f.name) }
        stopScan()
    }

    fun measure() {
        val address = _state.value.address ?: return
        if (_state.value.measuring) return
        stopScan()
        val rr = ArrayList<Double>()          // intervalli dopo l'assestamento
        var firstDataAt = 0L
        _state.update { it.copy(measuring = true, status = "Collegamento alla fascia…", secondsLeft = SETTLE_SECONDS + MEASURE_SECONDS, beats = 0, liveHr = null) }
        strap.connect(address,
            onState = { st ->
                if (st != "connesso" && st != "pronto") _state.update { it.copy(status = st) }
            },
            onData = { m ->
                val now = System.currentTimeMillis()
                synchronized(rr) {
                    if (firstDataAt == 0L) firstDataAt = now
                    if (now - firstDataAt >= SETTLE_SECONDS * 1000L) rr.addAll(m.rrMs)
                }
                _state.update { it.copy(liveHr = m.heartRate, beats = synchronized(rr) { rr.size }) }
            },
        )
        job = viewModelScope.launch {
            // Attende i primi dati (massimo 20 secondi).
            var waited = 0
            while (synchronized(rr) { firstDataAt } == 0L && waited < 20) { delay(1000); waited++ }
            val start = synchronized(rr) { firstDataAt }
            if (start == 0L) {
                strap.close()
                _state.update { it.copy(measuring = false, status = "Nessun dato dalla fascia. Inumidisci gli elettrodi, indossala e riprova.") }
                return@launch
            }
            val total = SETTLE_SECONDS + MEASURE_SECONDS
            while (true) {
                val elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
                if (elapsed >= total) break
                _state.update {
                    it.copy(secondsLeft = total - elapsed,
                        status = if (elapsed < SETTLE_SECONDS) "Assestamento: resta fermo e respira normalmente" else "Misura in corso")
                }
                delay(500)
            }
            strap.close()
            val data = synchronized(rr) { rr.toList() }
            val r = Hrv.rmssd(data)
            if (r.rmssd == null || r.meanHr == null) {
                _state.update {
                    it.copy(measuring = false, secondsLeft = 0,
                        status = "Misura non affidabile: ${r.pairs} coppie di battiti valide, qualità ${(r.coverage * 100).toInt()}%. " +
                            "La fascia deve inviare gli intervalli RR e il contatto deve essere buono.")
                }
                return@launch
            }
            val m = StrapMeasurement(System.currentTimeMillis(), LocalDate.now(), r.rmssd, r.meanHr, MEASURE_SECONDS, r.coverage,
                _state.value.name ?: address)
            val h = withContext(Dispatchers.IO) { store.add(m); store.load() }
            _state.update { it.copy(measuring = false, secondsLeft = 0, status = "Misura salvata", last = m, history = h) }
        }
    }

    fun cancel() {
        job?.cancel(); strap.close()
        _state.update { it.copy(measuring = false, secondsLeft = 0, status = "Misura annullata") }
    }

    fun remove(m: StrapMeasurement) = viewModelScope.launch {
        val h = withContext(Dispatchers.IO) { store.remove(m.timeMs); store.load() }
        _state.update { it.copy(history = h, last = it.last?.takeIf { l -> l.timeMs != m.timeMs }) }
    }

    override fun onCleared() { strap.stopScan(); strap.close() }

    companion object {
        const val SETTLE_SECONDS = 30
        const val MEASURE_SECONDS = 180
    }
}
