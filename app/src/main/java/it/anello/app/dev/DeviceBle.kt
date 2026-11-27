package it.anello.app.dev

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import it.anello.app.ble.HrStrap
import it.anello.app.ble.OuraBle
import it.anello.app.ble.RingBle
import it.anello.app.data.Sample
import it.anello.app.data.Sources
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Operazioni sui dispositivi diversi dagli anelli Oura: ricerca, esplorazione dei servizi
 * standard e sincronizzazione degli anelli Colmi e RingConn.
 */
@SuppressLint("MissingPermission")
class DeviceBle(private val context: Context, private val store: DeviceStore) {

    data class Found(val name: String, val address: String, val rssi: Int)

    data class Explored(
        val address: String,
        val name: String,
        val family: Profiles.Family?,
        val battery: Int?,
        val lines: List<String>,
        val error: String? = null,
    )

    private val adapter by lazy { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    private var scanCb: ScanCallback? = null

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Cerca tutti i dispositivi Bluetooth LE vicini che dichiarano un nome. */
    fun startScan(onFound: (Found) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (name.isBlank()) return
                onFound(Found(name, result.device.address, result.rssi))
            }
        }
        scanCb = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    fun stopScan() {
        scanCb?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCb = null
    }

    private fun device(address: String): BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    // --- Esplorazione: solo lettura di profili standard ---------------------------------------

    suspend fun explore(address: String, name: String, log: (String) -> Unit): Explored = RingBle.bleLock.withLock {
        val dev = device(address) ?: return@withLock Explored(address, name, null, null, emptyList(), "indirizzo non valido")
        val link = GattLink(context, log)
        try {
            if (!link.connect(dev)) return@withLock Explored(address, name, null, null, emptyList(), link.lastError ?: "connessione non riuscita")
            val services = link.services()
            val family = Profiles.family(services.map { it.uuid })
            val lines = ArrayList<String>()
            var battery: Int? = null

            fun svc(short: Int) = Profiles.uuid16(short)
            suspend fun readText(label: String, chr: Int) {
                link.read(svc(Profiles.SVC_DEVICE_INFO), svc(chr))?.let { v ->
                    Profiles.text(v).takeIf { it.isNotEmpty() }?.let { lines += "$label: $it" }
                }
            }
            if (link.has(svc(Profiles.SVC_DEVICE_INFO))) {
                readText("Produttore", Profiles.CHR_MANUFACTURER)
                readText("Modello", Profiles.CHR_MODEL)
                readText("Firmware", Profiles.CHR_FIRMWARE)
                readText("Hardware", Profiles.CHR_HARDWARE)
            }
            link.read(svc(Profiles.SVC_BATTERY), svc(Profiles.CHR_BATTERY))?.let { v ->
                battery = Profiles.battery(v)
                battery?.let { lines += "Batteria: $it%" }
            }

            // Misure standard dal vivo: si ascolta per qualche secondo.
            val live = listOf(
                Profiles.SVC_HEART_RATE to Profiles.CHR_HEART_RATE,
                Profiles.SVC_THERMOMETER to Profiles.CHR_TEMPERATURE,
                Profiles.SVC_PULSE_OX to Profiles.CHR_PLX_CONTINUOUS,
                Profiles.SVC_PULSE_OX to Profiles.CHR_PLX_SPOT,
                Profiles.SVC_CYCLING_POWER to Profiles.CHR_CYCLING_POWER,
            ).filter { (s, c) ->
                val chr = services.firstOrNull { it.uuid == svc(s) }?.getCharacteristic(svc(c))
                chr != null && chr.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val seen = LinkedHashMap<Int, String>()
            if (live.isNotEmpty()) {
                link.drain()
                live.forEach { (s, c) -> link.subscribe(svc(s), svc(c)) }
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && seen.size < live.size) {
                    val (uuid, v) = link.next(deadline - System.currentTimeMillis()) ?: break
                    when (Profiles.short(uuid)) {
                        Profiles.CHR_HEART_RATE -> HrStrap.parse(v)?.let {
                            seen[Profiles.CHR_HEART_RATE] = "Battito dal vivo: ${it.heartRate} bpm" +
                                (if (it.rrMs.isNotEmpty()) " (invia gli intervalli RR: adatto alla misura dell'HRV)" else "")
                        }
                        Profiles.CHR_TEMPERATURE -> Profiles.temperature(v)?.let { seen[Profiles.CHR_TEMPERATURE] = "Temperatura: %.1f °C".format(it) }
                        Profiles.CHR_PLX_CONTINUOUS, Profiles.CHR_PLX_SPOT -> Profiles.pulseOx(v)?.let {
                            seen[Profiles.CHR_PLX_SPOT] = "SpO2: %.0f%% · polso %.0f bpm".format(it.first, it.second)
                        }
                        Profiles.CHR_CYCLING_POWER -> Profiles.cyclingPower(v)?.let { seen[Profiles.CHR_CYCLING_POWER] = "Potenza: $it W" }
                    }
                }
            }
            lines += seen.values
            if (live.any { it.first == Profiles.SVC_HEART_RATE }) lines += "Espone il battito standard: si può usare nella sezione «Fascia cardio»."

            val names = services.map { Profiles.serviceName(it.uuid) }.distinct()
            lines += "Servizi: " + names.joinToString(", ")
            Explored(address, name, family, battery, lines)
        } catch (e: Exception) {
            Explored(address, name, null, null, emptyList(), e.message ?: e.toString())
        } finally {
            link.close()
        }
    }

    // --- Sincronizzazione ---------------------------------------------------------------------

    /** Sincronizza un dispositivo aggiunto. Restituisce un messaggio per l'utente e se è andata bene. */
    suspend fun sync(address: String, history: Boolean, log: (String) -> Unit): Pair<String, Boolean> = RingBle.bleLock.withLock {
        val d = store.get(address) ?: return@withLock Pair("dispositivo non aggiunto", false)
        val result = try {
            when (d.kind) {
                DeviceStore.COLMI -> syncColmi(d, log)
                DeviceStore.RINGCONN -> syncRingConn(d, history, log)
                else -> Pair("tipo non supportato", false)
            }
        } catch (e: Exception) {
            Pair(e.message ?: e.toString(), false)
        }
        val cur = store.get(address) ?: d
        store.save(cur.copy(lastSync = if (result.second) System.currentTimeMillis() else cur.lastSync, message = result.first))
        log(result.first)
        result
    }

    private suspend fun syncColmi(d: DeviceStore.Device, log: (String) -> Unit): Pair<String, Boolean> {
        val dev = device(d.address) ?: return Pair("indirizzo non valido", false)
        val link = GattLink(context, log)
        try {
            if (!link.connect(dev)) return Pair(link.lastError ?: "connessione non riuscita", false)
            if (!link.has(Colmi.UART_SERVICE)) return Pair("questo dispositivo non usa il protocollo Colmi", false)
            if (!link.subscribe(Colmi.UART_SERVICE, Colmi.UART_TX)) return Pair(link.lastError ?: "notifiche rifiutate", false)

            suspend fun send(p: ByteArray): Boolean = link.write(Colmi.UART_SERVICE, Colmi.UART_RX, p)

            /** Invia un comando e passa a [onPacket] le risposte con lo stesso comando finché restituisce true o c'è silenzio. */
            suspend fun exchange(p: ByteArray, quietMs: Long = 3_000, onPacket: (ByteArray) -> Boolean) {
                link.drain()
                if (!send(p)) return
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    val (uuid, v) = link.next(quietMs) ?: return
                    if (uuid != Colmi.UART_TX || v.size != 16 || v[0] != p[0]) continue
                    if (onPacket(v)) return
                }
            }

            val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
            send(Colmi.setTime(nowUtc))
            delay(300)

            var battery: Int? = null
            exchange(Colmi.battery()) { p -> Colmi.parseBattery(p)?.let { battery = it.first }; true }
            battery?.let { log("Batteria $it%"); store.save(d.copy(battery = it)) }

            // Misura periodica del battito: senza, il registro resta vuoto.
            var hrSettings: Pair<Boolean, Int>? = null
            exchange(Colmi.readHrSettings()) { p -> hrSettings = Colmi.parseHrSettings(p); true }
            hrSettings.let { s ->
                if (s != null && !s.first) {
                    send(Colmi.setHrSettings(true, 5))
                    log("Misura periodica del battito attivata (ogni 5 minuti).")
                } else if (s != null) log("Misura periodica del battito: ogni ${s.second} minuti.")
            }

            val slots = ArrayList<Colmi.StepSlot>()
            for (offset in 0..6) {
                val parser = Colmi.StepsParser()
                var done: List<Colmi.StepSlot>? = null
                exchange(Colmi.readSteps(offset)) { p -> done = parser.feed(p); done != null }
                slots += done ?: parser.partial()
                if (!link.isConnected) break
            }
            log("Passi: ${slots.size} intervalli di 15 minuti.")

            val hr = ArrayList<Sample>()
            val todayUtc = LocalDate.now(ZoneOffset.UTC)
            for (offset in 0..6) {
                val midnight = todayUtc.minusDays(offset.toLong()).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val parser = Colmi.HrLogParser()
                exchange(Colmi.readHrLog(midnight)) { p -> parser.feed(p) }
                hr += parser.samples()
                if (!link.isConnected) break
            }
            log("Battito: ${hr.size} misure.")

            var sleeps = emptyList<Colmi.SleepNight>()
            if (link.has(Colmi.BIG_SERVICE) && link.subscribe(Colmi.BIG_SERVICE, Colmi.BIG_NOTIFY)) {
                link.drain()
                if (link.write(Colmi.BIG_SERVICE, Colmi.BIG_WRITE, Colmi.bigRequest(Colmi.BIG_SLEEP))) {
                    val big = Colmi.BigData()
                    val deadline = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < deadline) {
                        val (uuid, v) = link.next(4_000) ?: break
                        if (uuid != Colmi.BIG_NOTIFY) continue
                        val full = big.feed(v) ?: continue
                        if (full.first == Colmi.BIG_SLEEP) sleeps = Colmi.parseSleep(full.second)
                        break
                    }
                }
            }
            log(if (sleeps.isEmpty()) "Sonno: l'anello non ha risposto, la notte viene stimata." else "Sonno: ${sleeps.size} notti.")

            val days = Colmi.buildDays(slots, hr.sortedBy { it.tMs }, sleeps)
            if (days.isNotEmpty()) Sources.store(context, Sources.device(d.address)).upsert(days)
            val nights = days.count { it.hasSleep }
            return Pair("Sincronizzato: ${days.size} giorni, $nights notti.", slots.isNotEmpty() || hr.isNotEmpty() || battery != null)
        } finally {
            link.close()
        }
    }

    private suspend fun syncRingConn(d: DeviceStore.Device, history: Boolean, log: (String) -> Unit): Pair<String, Boolean> {
        val dev = device(d.address) ?: return Pair("indirizzo non valido", false)
        // Senza accoppiamento di sistema l'anello accetta l'autenticazione ma ignora i comandi.
        if (dev.bondState != BluetoothDevice.BOND_BONDED && !OuraBle(context, log).bond(dev)) {
            return Pair("accoppiamento Bluetooth non riuscito", false)
        }
        val link = GattLink(context, log)
        try {
            if (!link.connect(dev)) return Pair(link.lastError ?: "connessione non riuscita", false)
            if (!link.has(RingConn.SERVICE)) return Pair("questo dispositivo non usa il protocollo RingConn", false)
            if (!link.subscribe(RingConn.SERVICE, RingConn.NOTIFY)) return Pair(link.lastError ?: "notifiche rifiutate", false)

            suspend fun send(p: ByteArray): Boolean = link.write(RingConn.SERVICE, RingConn.WRITE, p)
            suspend fun await(id: Int, timeoutMs: Long): ByteArray? {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    val (_, v) = link.next(left) ?: return null
                    if (v.isNotEmpty() && (v[0].toInt() and 0xff) == id) return v
                }
            }

            link.drain()
            send(RingConn.HELLO)
            val challenge = await(RingConn.RESP_STATUS, 4_000) ?: return Pair("l'anello non ha risposto all'autenticazione", false)
            val reply = RingConn.authReply(challenge, d.address) ?: return Pair("risposta di autenticazione non valida", false)
            send(reply)
            if (await(RingConn.RESP_STATUS, 4_000) == null) return Pair("autenticazione rifiutata", false)
            log("Autenticato.")

            // Stato: 07 sveglia la telemetria; alcune versioni rispondono solo a d0.
            var desc: RingConn.Descriptor? = null
            send(RingConn.FETCH)
            var f = await(RingConn.RESP_DESCRIPTOR_FETCH, 5_000)
            if (f == null) { send(RingConn.STATUS_QUERY); f = await(RingConn.RESP_DESCRIPTOR_QUERY, 5_000) }
            f?.let { desc = RingConn.parseDescriptor(it) }
            desc?.let {
                log("Batteria ${it.battery}%%${if (it.charging) " (in carica)" else ""} · passi oggi ${it.steps} · pelle %.1f °C".format(it.skinTempA))
                store.save(d.copy(battery = it.battery))
                Sources.store(context, Sources.device(d.address)).upsertActivity(mapOf(LocalDate.now() to Pair(it.steps.toLong(), null)))
            }

            var records = 0
            if (history) {
                for (channel in intArrayOf(RingConn.CHANNEL_SLEEP, RingConn.CHANNEL_ALL_DAY)) {
                    records += drain(link, d.address, channel, log)
                    if (!link.isConnected) break
                }
                log("Storico: $records nuovi record.")
            }

            val since = System.currentTimeMillis() / 1000 - 10 * 86_400L
            val epochs = store.loadRecords(d.address, since).map { RingConn.toEpoch(it) }
            val nights = DeviceNights.build(epochs, RingConn.EPOCH_SECONDS * 1000)
                .filter { it.date >= LocalDate.now().minusDays(8) }
            if (nights.isNotEmpty()) Sources.store(context, Sources.device(d.address)).upsertNights(nights)
            val msg = if (history) "Sincronizzato: $records record, ${nights.size} notti stimate." else "Stato letto (storico non scaricato)."
            return Pair(msg, desc != null || records > 0)
        } finally {
            link.close()
        }
    }

    /** Scarica un canale dello storico. Ogni pagina viene salvata su disco prima di confermarla, perché la conferma la cancella dall'anello. */
    private suspend fun drain(link: GattLink, address: String, channel: Int, log: (String) -> Unit): Int {
        suspend fun send(p: ByteArray): Boolean = link.write(RingConn.SERVICE, RingConn.WRITE, p)
        send(RingConn.syncOpen(System.currentTimeMillis() / 1000, channel))
        var count = 0
        var pages = 0
        var quiet = 0
        var opened = false
        var lastDescriptor: ByteArray? = null
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && link.isConnected) {
            val n = link.next(1_000)
            if (n == null) {
                quiet++
                if (!opened && quiet >= 4) { opened = true; send(RingConn.FETCH); quiet = 0; continue }
                if (quiet >= (if (pages == 0) 12 else 3)) break
                continue
            }
            quiet = 0
            val frame = n.second
            if (!RingConn.valid(frame)) continue
            when (frame[0].toInt() and 0xff) {
                RingConn.RESP_SYNC_OPEN -> {
                    if (frame.size > 1 && (frame[1].toInt() and 0xff) == 0xff) return count // niente da scaricare
                    if (!opened) { opened = true; send(RingConn.FETCH) }
                }
                RingConn.RESP_PAGE_4C -> {
                    val recs = RingConn.parsePage(frame)
                    store.appendRecords(address, recs)
                    send(RingConn.ACK_4C)
                    count += recs.size
                    pages++
                }
                RingConn.RESP_PAGE_4D -> { send(RingConn.ACK_4D); pages++ }
                RingConn.RESP_PAGE_47 -> { send(RingConn.ACK_47); pages++ }
                RingConn.RESP_HEARTBEAT -> send(RingConn.HEARTBEAT_ACK)
                RingConn.RESP_DESCRIPTOR_FETCH, RingConn.RESP_DESCRIPTOR_QUERY -> {
                    if (lastDescriptor == null || !frame.contentEquals(lastDescriptor)) {
                        lastDescriptor = frame.copyOf()
                        send(RingConn.FETCH)
                    }
                }
                RingConn.RESP_END -> return count
            }
        }
        if (pages == 0) log("Canale $channel: nessuna pagina.")
        return count
    }
}
