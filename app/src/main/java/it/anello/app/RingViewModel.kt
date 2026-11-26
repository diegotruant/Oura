package it.anello.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.RingAggregate
import it.anello.app.ble.RingBle
import it.anello.app.ble.RingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RingUiState(
    val scanning: Boolean = false,
    val devices: List<RingBle.Found> = emptyList(),
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
    val info: RingBle.RingInfo? = null,
    val aggregate: RingAggregate? = null,
    val rawCount: Int = 0,
    val selected: String? = null,
    val selectedKeyHex: String? = null,
)

class RingViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RingStore(app)
    private val ble = RingBle(app, store)

    private val _state = MutableStateFlow(RingUiState(rawCount = store.rawCount()))
    val state: StateFlow<RingUiState> = _state

    private fun log(line: String) = _state.update {
        it.copy(log = (it.log + line).takeLast(200))
    }

    fun bluetoothOn() = ble.isBluetoothOn()

    fun startScan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, devices = emptyList()) }
        log("Scansione avviata…")
        ble.startScan { found ->
            _state.update { s ->
                val others = s.devices.filterNot { it.address == found.address }
                s.copy(devices = (others + found).sortedByDescending { it.rssi })
            }
        }
    }

    fun stopScan() {
        ble.stopScan()
        _state.update { it.copy(scanning = false) }
    }

    fun select(address: String) {
        _state.update { it.copy(selected = address, selectedKeyHex = store.keyHex(address), info = null) }
    }

    fun readInfo() = run("Lettura info") { addr -> _state.update { it.copy(info = ble.readInfo(addr, ::log)) } }

    fun pair() = run("Associazione") { addr ->
        val key = ble.pair(addr, ::log)
        _state.update { it.copy(selectedKeyHex = key ?: it.selectedKeyHex) }
    }

    fun sync() = run("Sincronizzazione") { addr ->
        val res = ble.sync(addr, ::log)
        if (res.error != null) log("Errore: ${res.error}")
        _state.update { it.copy(aggregate = res.aggregate, rawCount = store.rawCount()) }
    }

    /** Importa manualmente una chiave di 16 byte (32 caratteri esadecimali). */
    fun importKey(hex: String) {
        val addr = _state.value.selected ?: return
        val clean = hex.trim().replace(" ", "").lowercase()
        if (clean.length != 32 || !clean.all { it in "0123456789abcdef" }) {
            log("Chiave non valida: servono 32 caratteri esadecimali.")
            return
        }
        store.saveKey(addr, RingStore.hexToBytes(clean))
        _state.update { it.copy(selectedKeyHex = clean) }
        log("Chiave importata.")
    }

    fun exportRawPath(): String = store.rawFilePath()

    fun clearRaw() {
        store.clearRaw()
        _state.update { it.copy(rawCount = 0, aggregate = null) }
        log("Log eventi grezzi cancellato.")
    }

    private fun run(label: String, block: suspend (String) -> Unit) {
        val addr = _state.value.selected
        if (addr == null) { log("Seleziona prima un anello."); return }
        if (_state.value.busy) return
        stopScan()
        _state.update { it.copy(busy = true) }
        log("$label in corso…")
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block(addr) } }
            catch (e: Exception) { log("Errore: ${e.message}") }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() { ble.stopScan() }
}
