package it.anello.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.RingDataBus
import it.anello.app.dev.DeviceBle
import it.anello.app.dev.DeviceStore
import it.anello.app.dev.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DevicesUiState(
    val scanning: Boolean = false,
    val found: List<DeviceBle.Found> = emptyList(),
    val busy: Boolean = false,
    val explored: DeviceBle.Explored? = null,
    val devices: List<DeviceStore.Device> = emptyList(),
    val log: List<String> = emptyList(),
)

/** Altri dispositivi Bluetooth: ricerca, esplorazione e sincronizzazione di Colmi e RingConn. */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    private val ble = DeviceBle(app, store)

    private val _state = MutableStateFlow(DevicesUiState(devices = store.devices()))
    val state: StateFlow<DevicesUiState> = _state

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(60)) }

    fun bluetoothOn() = ble.isBluetoothOn()

    fun startScan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, found = emptyList(), explored = null) }
        ble.startScan { f ->
            _state.update { st ->
                val rest = st.found.filterNot { it.address == f.address }
                st.copy(found = (rest + f).sortedByDescending { it.rssi }.take(25))
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(20_000)
            stopScan()
        }
    }

    fun stopScan() {
        ble.stopScan()
        _state.update { it.copy(scanning = false) }
    }

    /** Si collega al dispositivo e ne legge i profili standard, senza modificare niente. */
    fun explore(f: DeviceBle.Found) {
        if (_state.value.busy) return
        stopScan()
        _state.update { it.copy(busy = true, explored = null, log = emptyList()) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { ble.explore(f.address, f.name, ::log) }
            _state.update { it.copy(busy = false, explored = r) }
        }
    }

    /** Aggiunge il dispositivo esplorato alle fonti e lo sincronizza. */
    fun add(history: Boolean) {
        val e = _state.value.explored ?: return
        val kind = when (e.family) {
            Profiles.Family.COLMI -> DeviceStore.COLMI
            Profiles.Family.RINGCONN -> DeviceStore.RINGCONN
            else -> return
        }
        store.save(DeviceStore.Device(e.address, kind, e.name, e.battery))
        _state.update { it.copy(devices = store.devices(), explored = null, found = emptyList()) }
        RingDataBus.updated.value = System.currentTimeMillis()
        sync(e.address, history)
    }

    fun sync(address: String, history: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, log = emptyList()) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ble.sync(address, history, ::log) }
            _state.update { it.copy(busy = false, devices = store.devices()) }
            RingDataBus.updated.value = System.currentTimeMillis()
        }
    }

    /** Toglie il dispositivo dall'elenco; i giorni già salvati restano nell'archivio. */
    fun remove(address: String) {
        store.remove(address)
        _state.update { it.copy(devices = store.devices()) }
        RingDataBus.updated.value = System.currentTimeMillis()
    }
}
