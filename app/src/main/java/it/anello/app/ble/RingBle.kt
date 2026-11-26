package it.anello.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.delay

/**
 * Orchestrazione di alto livello verso l'anello: scansione, lettura info,
 * associazione (pairing) e sincronizzazione eventi. Non modifica nulla
 * sull'anello se non quando chiami esplicitamente pair().
 */
@SuppressLint("MissingPermission")
class RingBle(private val context: Context, private val store: RingStore) {

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    data class Found(val name: String, val address: String, val rssi: Int)

    private var scanCb: ScanCallback? = null

    fun startScan(onFound: (Found) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(OuraProtocol.SERVICE))
            .build()
        // Alcuni anelli non annunciano il service UUID: teniamo anche un filtro per nome.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                val isOura = name.contains("Oura", true) ||
                    result.scanRecord?.serviceUuids?.contains(ParcelUuid(OuraProtocol.SERVICE)) == true ||
                    result.scanRecord?.getManufacturerSpecificData(OuraProtocol.MANUFACTURER_ID) != null
                if (isOura) onFound(Found(name.ifBlank { "Oura" }, result.device.address, result.rssi))
            }
        }
        scanCb = cb
        // Scansione senza filtro service (molti anelli usano RPA/nome): filtriamo noi.
        scanner.startScan(null, settings, cb)
    }

    fun stopScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanCb?.let { runCatching { scanner.stopScan(it) } }
        scanCb = null
    }

    private fun device(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    // --- Autenticazione comune ----------------------------------------------

    private suspend fun authenticate(ble: OuraBle, address: String, log: (String) -> Unit): Boolean {
        val key = store.keyFor(address) ?: run { log("Nessuna chiave salvata per questo anello."); return false }
        val nonceResp = ble.transact(OuraProtocol.getAuthNonce()) { it.size >= 18 && it[0] == 0x2f.toByte() }
            .lastOrNull()
        val nonce = nonceResp?.let { OuraProtocol.parseNonce(it) }
        if (nonce == null) { log("Nonce non ricevuto."); return false }
        val enc = OuraProtocol.encryptNonce(key, nonce)
        val authResp = ble.transact(OuraProtocol.authenticate(enc)) { it.size >= 4 && it[0] == 0x2f.toByte() }
            .lastOrNull()
        val ok = authResp != null && OuraProtocol.authOk(authResp)
        log(if (ok) "Autenticazione riuscita." else "Autenticazione fallita (chiave errata?).")
        return ok
    }

    // --- Lettura informazioni (sicura, non modifica niente) ------------------

    data class RingInfo(
        val connected: Boolean,
        val firmware: String? = null,
        val mac: String? = null,
        val battery: Int? = null,
        val hasKey: Boolean = false,
        val authed: Boolean = false,
        val error: String? = null,
    )

    suspend fun readInfo(address: String, log: (String) -> Unit): RingInfo {
        val dev = device(address) ?: return RingInfo(false, error = "indirizzo non valido")
        val ble = OuraBle(context)
        try {
            log("Connessione a $address…")
            if (!ble.connect(dev)) return RingInfo(false, error = ble.lastError ?: "connessione fallita")
            log("Connesso. Lettura firmware…")
            val fwResp = ble.transact(OuraProtocol.firmware()) { it.isNotEmpty() && it[0] == 0x09.toByte() }.lastOrNull()
            val info = fwResp?.let { OuraProtocol.parseFirmware(it) }
            info?.let { log("Firmware ${it.firmware}, MAC ${it.mac}") }

            val hasKey = store.keyFor(address) != null
            var authed = false
            var battery: Int? = null
            if (hasKey && authenticate(ble, address, log)) {
                authed = true
                val batResp = ble.transact(OuraProtocol.battery()) { it.isNotEmpty() && it[0] == 0x0d.toByte() }.lastOrNull()
                battery = batResp?.let { OuraProtocol.parseBattery(it)?.percent }
                battery?.let { log("Batteria $it%") }
            }
            return RingInfo(true, info?.firmware, info?.mac, battery, hasKey, authed)
        } catch (e: Exception) {
            return RingInfo(false, error = e.message)
        } finally {
            ble.close()
        }
    }

    // --- Associazione (installa una chiave; richiede anello RESETTATO) --------

    suspend fun pair(address: String, log: (String) -> Unit): String? {
        val dev = device(address) ?: return null
        val ble = OuraBle(context)
        try {
            log("Connessione per associazione…")
            if (!ble.connect(dev)) { log("Connessione fallita: ${ble.lastError}"); return null }
            val key = OuraProtocol.generateAuthKey()
            log("Invio della nuova chiave all'anello…")
            val resp = ble.transact(OuraProtocol.setAuthKey(key)) { it.isNotEmpty() && it[0] == 0x25.toByte() }.lastOrNull()
            if (resp == null || !OuraProtocol.setKeyOk(resp)) {
                log("L'anello ha rifiutato la chiave. Deve essere resettato di fabbrica e non associato ad altre app.")
                return null
            }
            log("Chiave accettata. Avvio del bonding Bluetooth…")
            ble.ensureBond(dev)
            delay(3_000) // lascia completare il pairing di sistema
            store.saveKey(address, key)
            // Riconnette per autenticarsi sul link cifrato e accendere le misure.
            ble.close()
            val ble2 = OuraBle(context)
            if (ble2.connect(dev) && authenticate(ble2, address, log)) {
                log("Accendo misure di battito e SpO2…")
                ble2.transact(OuraProtocol.enableDaytimeHr())
                ble2.transact(OuraProtocol.enableSpo2())
            }
            ble2.close()
            log("Associazione completata.")
            return RingStore.bytesToHex(key)
        } catch (e: Exception) {
            log("Errore: ${e.message}")
            return null
        } finally {
            ble.close()
        }
    }

    // --- Sincronizzazione eventi --------------------------------------------

    data class SyncResult(val newEvents: Int, val aggregate: RingAggregate?, val error: String? = null)

    suspend fun sync(address: String, log: (String) -> Unit): SyncResult {
        val dev = device(address) ?: return SyncResult(0, null, "indirizzo non valido")
        val ble = OuraBle(context)
        try {
            log("Connessione…")
            if (!ble.connect(dev)) return SyncResult(0, null, ble.lastError ?: "connessione fallita")
            if (!authenticate(ble, address, log)) return SyncResult(0, null, "autenticazione fallita")

            // Handshake stile app (letture e registrazioni non distruttive).
            log("Registrazione stream e parametri…")
            ble.transact(OuraProtocol.registerStream())
            for (s in OuraProtocol.categorySubscriptions) ble.transact(s)
            for (s in OuraProtocol.paramSweep) ble.transact(s)
            ble.transact(OuraProtocol.capabilities(0))
            log("Sincronizzo l'orologio dell'anello…")
            ble.transact(OuraProtocol.syncTimeLegacy(System.currentTimeMillis() / 1000))
            ble.transact(OuraProtocol.checkSleepAnalysis())

            // Drenaggio eventi dal cursore salvato.
            var cursor = store.cursor(address)
            log("Scarico eventi dal cursore $cursor…")
            val collected = ArrayList<OuraEvents.Event>()
            var tailPasses = 0
            var guard = 0
            while (guard++ < 200) {
                ble.transact(OuraProtocol.dataFlush())
                val frames = ble.transact(
                    OuraProtocol.getEventsLegacy(cursor),
                    quietMs = 2_000,
                    overallMs = 40_000,
                    terminal = { it.isNotEmpty() && it[0] == 0x11.toByte() },
                )
                val summary = frames.firstNotNullOfOrNull { OuraProtocol.parseLegacySummary(it) }
                val fresh = frames.filter { OuraEvents.isEventFrame(it) }
                    .mapNotNull { OuraEvents.parse(it) }
                    .filter { it.ringDecis >= cursor } // scarta i replay

                if (fresh.isNotEmpty()) {
                    store.appendEvents(address, fresh)
                    collected += fresh
                    val maxTs = fresh.maxOf { it.ringDecis }
                    cursor = maxTs + 1
                    store.setCursor(address, cursor)
                    log("…${collected.size} eventi (cursore $cursor)")
                }

                val bytesLeft = summary?.bytesLeft ?: 0L
                if (bytesLeft > 0) continue
                // Coda viva: accetta poche piccole passate poi fermati.
                if (fresh.size in 1..31 && tailPasses < 3) { tailPasses++; continue }
                break
            }

            val agg = RingAggregate.from(store.loadRaw())
            log("Fatto: ${collected.size} nuovi eventi.")
            return SyncResult(collected.size, agg)
        } catch (e: Exception) {
            return SyncResult(0, null, e.message)
        } finally {
            ble.close()
        }
    }
}
