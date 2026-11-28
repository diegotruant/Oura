package it.anello.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.RingAggregate
import it.anello.app.ble.RingBle
import it.anello.app.ble.RingStore
import it.anello.app.ble.RingStress
import it.anello.app.ble.RingNights
import it.anello.app.ble.RingDataBus
import it.anello.app.data.Sources
import it.anello.app.ble.RingSyncWorker
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
    val battery: Pair<Int, Long>? = null,
    val autoSync: Boolean = true,
    val lastSync: RingStore.LastSync? = null,
    /** Anelli associati: indirizzo e nome. */
    val paired: List<Pair<String, String>> = emptyList(),
    /** Ultimo stato letto della registrazione dei dati sull'andatura: 0 spenta, 1 attiva. */
    val stepDataMode: Int? = null,
)

class RingViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RingStore(app)
    private val ble = RingBle(app, store)

    private val _state = MutableStateFlow(RingUiState(
            rawCount = store.rawCount(),
            battery = store.lastBattery(store.lastAddress),
            selected = store.lastAddress,
            paired = pairedList(store),
            selectedKeyHex = store.lastAddress?.let { store.keyHex(it) },
            autoSync = store.autoSync,
            lastSync = store.lastSync(),
        ))
    val state: StateFlow<RingUiState> = _state

    private fun log(line: String) = _state.update {
        it.copy(log = (it.log + line).takeLast(200), battery = store.lastBattery(it.selected))
    }

    init {
        // Mostra subito i dati già salvati ed elabora le notti presenti nel log grezzo.
        viewModelScope.launch {
            val agg = withContext(Dispatchers.IO) {
                for (addr in store.pairedRings()) {
                    val raw = store.loadRaw(addr)
                    val nights = RingNights.buildRecent(raw)
                    if (nights.isNotEmpty()) Sources.store(getApplication(), Sources.ring(addr)).upsertNights(nights)
                    runCatching { RingStress.update(getApplication(), addr, raw, Sources.store(getApplication(), Sources.ring(addr)).load().values) }
                }
                store.lastAddress?.let { a -> store.loadRaw(a).takeIf { it.isNotEmpty() }?.let { RingAggregate.from(it) } }
            }
            RingDataBus.updated.value = System.currentTimeMillis()
            _state.update { it.copy(aggregate = agg) }
        }
    }

    fun bluetoothOn() = ble.isBluetoothOn()

    fun setAutoSync(enabled: Boolean) {
        store.autoSync = enabled
        RingSyncWorker.schedule(getApplication(), enabled)
        _state.update { it.copy(autoSync = enabled) }
    }

    /** Sincronizza all'apertura dell'app se è passato un po' dall'ultima volta. */
    fun syncOnOpen() {
        val s = _state.value
        if (!s.autoSync || s.busy || s.selected == null || s.selectedKeyHex == null) return
        if (!RingSyncWorker.canUseBluetooth(getApplication())) return
        val last = store.lastSync()?.time ?: 0L
        if (System.currentTimeMillis() - last < 10 * 60_000) return
        sync()
    }

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
        store.lastAddress = address
        // Ricorda il nome annunciato dall'anello, per riconoscerlo tra più anelli.
        _state.value.devices.firstOrNull { it.address == address }?.name?.takeIf { it.isNotBlank() }
            ?.let { store.saveName(address, it) }
        _state.update {
            it.copy(selected = address, selectedKeyHex = store.keyHex(address), info = null,
                battery = store.lastBattery(address), aggregate = null)
        }
    }

    fun readInfo() = run("Lettura info") { addr -> _state.update { it.copy(info = ble.readInfo(addr, ::log)) } }

    /** Accende o spegne sull'anello la registrazione dei dati sull'andatura (per il contapassi). */
    fun setStepData(enable: Boolean) = run(if (enable) "Attivazione dati sull'andatura" else "Disattivazione dati sull'andatura") { addr ->
        val mode = ble.stepData(addr, enable, ::log)
        _state.update { it.copy(stepDataMode = mode) }
    }

    fun pair() = run("Associazione") { addr ->
        val key = ble.pair(addr, ::log)
        _state.update { it.copy(selectedKeyHex = key ?: it.selectedKeyHex, paired = pairedList(store)) }
        RingDataBus.updated.value = System.currentTimeMillis()
    }

    fun sync() = run("Sincronizzazione") { addr ->
        val res = ble.sync(addr, ::log)
        if (res.error != null) log("Errore: ${res.error}")
        _state.update {
            it.copy(aggregate = res.aggregate ?: it.aggregate, rawCount = store.rawCount(), lastSync = store.lastSync())
        }
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
        _state.update { it.copy(selectedKeyHex = clean, paired = pairedList(store)) }
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

private fun pairedList(store: RingStore): List<Pair<String, String>> =
    store.pairedRings().map { it to (store.name(it)?.takeIf { n -> n.isNotBlank() } ?: "Anello") }
