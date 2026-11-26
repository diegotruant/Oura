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

    private fun isNonce(f: ByteArray) = f.size >= 18 && f[0] == 0x2f.toByte() && f[2] == 0x2c.toByte()
    private fun isAuthResp(f: ByteArray) = f.size >= 4 && f[0] == 0x2f.toByte() && f[2] == 0x2e.toByte()

    /**
     * Esegue nonce → AES → Authenticate con [key] e restituisce lo stato dell'anello:
     * 0x00 ok, 0x01 chiave errata, 0x02 anello resettato (nessuna chiave), 0x03 associato
     * a un'altra installazione. null se non arriva risposta.
     */
    private suspend fun authState(ble: OuraBle, key: ByteArray): Int? {
        // Alcuni anelli ogni tanto non rispondono al primo 2f012b: riproviamo.
        var nonce: ByteArray? = null
        for (attempt in 1..3) {
            nonce = ble.transact(OuraProtocol.getAuthNonce(), quietMs = 3_500) { isNonce(it) }
                .lastOrNull { isNonce(it) }?.let { OuraProtocol.parseNonce(it) }
            if (nonce != null || !ble.isConnected) break
        }
        if (nonce == null) return null
        val enc = OuraProtocol.encryptNonce(key, nonce)
        val resp = ble.transact(OuraProtocol.authenticate(enc)) { isAuthResp(it) }
            .lastOrNull { isAuthResp(it) } ?: return null
        return resp[3].toInt() and 0xff
    }

    private fun describeAuth(state: Int?): String = when (state) {
        0x00 -> "chiave corretta"
        0x01 -> "l'anello ha una chiave diversa (associato a Oura o a un altro telefono)"
        0x02 -> "anello resettato di fabbrica, pronto per l'associazione"
        0x03 -> "anello associato a un'altra installazione"
        null -> "nessuna risposta"
        else -> "stato sconosciuto 0x%02x".format(state)
    }

    private suspend fun authenticate(ble: OuraBle, address: String, log: (String) -> Unit): Boolean {
        val key = store.keyFor(address) ?: run { log("Nessuna chiave salvata per questo anello."); return false }
        val st = authState(ble, key)
        log("Autenticazione: ${describeAuth(st)}.")
        return st == 0x00
    }

    /** Legge la batteria (richiede autenticazione) e la salva. */
    private suspend fun readBattery(ble: OuraBle, log: (String) -> Unit): Int? {
        val bat = ble.transact(OuraProtocol.battery()) { it.isNotEmpty() && it[0] == 0x0d.toByte() }
            .lastOrNull { it.isNotEmpty() && it[0] == 0x0d.toByte() }
        val pct = bat?.let { OuraProtocol.parseBattery(it)?.percent }?.takeIf { it in 0..100 }
        if (pct != null) { store.saveBattery(pct); log("Batteria $pct%") }
        return pct
    }

    // --- Lettura informazioni + diagnosi (non modifica niente) ---------------

    data class RingInfo(
        val connected: Boolean,
        val firmware: String? = null,
        val mac: String? = null,
        val battery: Int? = null,
        val hasKey: Boolean = false,
        val authed: Boolean = false,
        val diagnosis: String? = null,
        val pairable: Boolean = false,
        val error: String? = null,
    )

    suspend fun readInfo(address: String, log: (String) -> Unit): RingInfo {
        val dev = device(address) ?: return RingInfo(false, error = "indirizzo non valido")
        val ble = OuraBle(context, log)
        try {
            log("Connessione a $address (accoppiato: ${dev.bondState == BluetoothDevice.BOND_BONDED})…")
            if (!ble.connect(dev)) {
                val hint = "Connessione rifiutata. Se l'anello è ancora associato all'app Oura " +
                    "chiude il collegamento con telefoni sconosciuti: serve il reset di fabbrica."
                log(hint)
                return RingInfo(false, error = ble.lastError, diagnosis = hint)
            }
            val fwResp = ble.transact(OuraProtocol.firmware()) { it.isNotEmpty() && it[0] == 0x09.toByte() }
                .lastOrNull { it.isNotEmpty() && it[0] == 0x09.toByte() }
            val info = fwResp?.let { OuraProtocol.parseFirmware(it) }
            info?.let { log("Firmware ${it.firmware}, API ${it.api}, MAC ${it.mac}") }

            val savedKey = store.keyFor(address)
            var authed = false
            var battery: Int? = null
            val diagnosis: String
            var pairable = false
            if (savedKey != null) {
                val st = authState(ble, savedKey)
                diagnosis = describeAuth(st)
                authed = st == 0x00
                pairable = st == 0x02
            } else {
                // Prova con una chiave fittizia: la risposta dice se l'anello è resettato.
                val st = authState(ble, ByteArray(16))
                diagnosis = describeAuth(st)
                pairable = st == 0x02
            }
            log("Diagnosi: $diagnosis.")
            if (authed) battery = readBattery(ble, log)
            return RingInfo(true, info?.firmware, info?.mac, battery, savedKey != null, authed, diagnosis, pairable)
        } catch (e: Exception) {
            return RingInfo(false, error = e.message)
        } finally {
            ble.close()
        }
    }

    // --- Associazione (installa una chiave; richiede anello RESETTATO) --------

    suspend fun pair(address: String, log: (String) -> Unit): String? {
        val dev = device(address) ?: return null
        val ble = OuraBle(context, log)
        try {
            // Un anello resettato accetta l'accoppiamento di sistema: facciamolo subito.
            if (dev.bondState != BluetoothDevice.BOND_BONDED) ble.bond(dev)
            if (!ble.connect(dev)) {
                log("Connessione fallita (${ble.lastError}). Se l'anello è ancora associato a Oura va resettato.")
                return null
            }
            val st = authState(ble, ByteArray(16))
            log("Stato anello: ${describeAuth(st)}.")
            if (st == 0x01 || st == 0x03) {
                log("Associazione annullata: l'anello ha già una chiave. Fai il reset di fabbrica e riprova.")
                return null
            }
            // Riusa la chiave già salvata se c'è (stesso anello riassociato), altrimenti nuova.
            val key = store.keyFor(address) ?: OuraProtocol.generateAuthKey()
            log("Invio della chiave all'anello…")
            val resp = ble.transact(OuraProtocol.setAuthKey(key)) { it.isNotEmpty() && it[0] == 0x25.toByte() }
                .lastOrNull { it.isNotEmpty() && it[0] == 0x25.toByte() }
            if (resp == null || !OuraProtocol.setKeyOk(resp)) {
                log("Chiave rifiutata (risposta ${resp?.let { OuraBle.hex(it) } ?: "assente"}).")
                return null
            }
            store.saveKey(address, key)
            log("Chiave installata e salvata.")
            if (authState(ble, key) == 0x00) {
                log("Autenticazione riuscita. Accendo le misure di battito e SpO2…")
                ble.transact(OuraProtocol.enableDaytimeHr())
                ble.transact(OuraProtocol.enableSpo2())
            } else {
                log("Chiave installata ma l'autenticazione non è ancora riuscita: riprova con Leggi info.")
            }
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
        val ble = OuraBle(context, log)
        try {
            log("Connessione…")
            if (!ble.connect(dev)) return SyncResult(0, null, ble.lastError ?: "connessione fallita")
            if (!authenticate(ble, address, log)) return SyncResult(0, null, "autenticazione fallita")
            readBattery(ble, log)

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
            while (true) {
                if (++guard > 300) { log("Interrotto dopo 300 lotti per sicurezza."); break }
                ble.transact(OuraProtocol.dataFlush(), verbose = false)
                val frames = ble.transact(
                    OuraProtocol.getEventsLegacy(cursor),
                    quietMs = 2_000,
                    overallMs = 40_000,
                    verbose = false,
                    terminal = { it.isNotEmpty() && it[0] == 0x11.toByte() },
                )
                val summary = frames.firstNotNullOfOrNull { OuraProtocol.parseLegacySummary(it) }
                log("Lotto: ${frames.size} frame, riepilogo ${summary?.let { "eventi ${it.events}, restanti ${it.bytesLeft} B" } ?: "assente"}")
                if (frames.isNotEmpty() && summary == null) log("Primo frame: ${OuraBle.hex(frames.first())}")
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
