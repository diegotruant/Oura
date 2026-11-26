package it.anello.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trasporto BLE verso l'anello: bonding, connessione, notifiche, scrittura comandi
 * e raccolta delle risposte come coroutine. Un comando alla volta (Mutex).
 * Ogni byte inviato/ricevuto viene passato a [log] in esadecimale per la diagnosi.
 */
@SuppressLint("MissingPermission")
class OuraBle(private val context: Context, private val log: (String) -> Unit = {}) {

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val notifications = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val txMutex = Mutex()

    private var connected: CompletableDeferred<Int>? = null
    private var servicesReady: CompletableDeferred<Boolean>? = null
    private var mtuReady: CompletableDeferred<Int>? = null
    private var writeAck: CompletableDeferred<Int>? = null
    private var descriptorAck: CompletableDeferred<Int>? = null

    @Volatile var isConnected = false
        private set

    var lastError: String? = null
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connected?.complete(0)
            } else {
                isConnected = false
                lastError = "collegamento chiuso (stato $status${reasonOf(status)})"
                connected?.complete(if (status == 0) -1 else status)
                servicesReady?.complete(false)
                writeAck?.complete(-1)
                descriptorAck?.complete(-1)
                mtuReady?.complete(-1)
                notifications.trySend(ByteArray(0)) // sveglia chi è in attesa
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(OuraProtocol.SERVICE)
            if (svc == null) {
                lastError = "servizio Oura non trovato (status $status)"
                servicesReady?.complete(false)
                return
            }
            writeChar = svc.getCharacteristic(OuraProtocol.WRITE_CHAR)
            notifyChar = svc.getCharacteristic(OuraProtocol.NOTIFY_CHAR)
            servicesReady?.complete(writeChar != null && notifyChar != null)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuReady?.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorAck?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status)
        }

        @Deprecated("deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { notifications.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(value.copyOf())
        }
    }

    // --- Bonding -------------------------------------------------------------

    /**
     * Accoppia il telefono con l'anello a livello di sistema (cifratura del link).
     * Un anello resettato accetta il bonding; uno già associato ad altri lo rifiuta.
     */
    suspend fun bond(device: BluetoothDevice, timeoutMs: Long = 30_000): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            log("Accoppiamento Bluetooth di sistema…")
            if (!device.createBond()) {
                lastError = "il sistema ha rifiutato di avviare l'accoppiamento"
                return device.bondState == BluetoothDevice.BOND_BONDED
            }
            val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
            log(if (ok) "Accoppiato." else "Accoppiamento non riuscito.")
            return ok
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // --- Connessione ---------------------------------------------------------

    /** Connette (con alcuni tentativi), scopre i servizi e attiva le notifiche. */
    suspend fun connect(device: BluetoothDevice, attempts: Int = 3): Boolean {
        for (i in 1..attempts) {
            if (i > 1) log("Nuovo tentativo di connessione ($i/$attempts)…")
            if (connectOnce(device)) return true
            close()
            delay(1_500)
        }
        return false
    }

    private suspend fun connectOnce(device: BluetoothDevice): Boolean {
        lastError = null
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        val st = withTimeoutOrNull(15_000) { connected?.await() }
        if (st != 0) {
            lastError = lastError ?: "nessuna risposta dall'anello entro 15 s"
            log("Connessione: ${lastError}")
            return false
        }
        log("Collegato. Ricerca servizi…")
        gatt?.discoverServices()
        val svc = withTimeoutOrNull(15_000) { servicesReady?.await() } ?: false
        if (!svc) {
            lastError = lastError ?: "ricerca servizi scaduta"
            log("Servizi: $lastError")
            return false
        }
        // MTU più grande: utile ma non indispensabile.
        mtuReady = CompletableDeferred()
        gatt?.requestMtu(247)
        val mtu = withTimeoutOrNull(3_000) { mtuReady?.await() }
        log("MTU ${mtu ?: "predefinito"}.")

        if (enableNotifications()) return true
        // Notifiche rifiutate: tipicamente serve il link cifrato (bonding).
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("Le notifiche richiedono l'accoppiamento.")
            if (bond(device) && isConnected && enableNotifications()) return true
        }
        lastError = lastError ?: "attivazione notifiche rifiutata"
        log("Notifiche: $lastError")
        return false
    }

    private suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val c = notifyChar ?: return false
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(OuraProtocol.CCCD) ?: return false
        descriptorAck = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        val status = withTimeoutOrNull(8_000) { descriptorAck?.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            lastError = "notifiche: stato ${status ?: "nessuna risposta"}${reasonOf(status ?: -1)}"
            return false
        }
        return true
    }

    private suspend fun write(cmd: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        writeAck = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = cmd
                g.writeCharacteristic(c)
            }
        }
        if (!started) { lastError = "scrittura non avviata"; return false }
        val st = withTimeoutOrNull(5_000) { writeAck?.await() }
        if (st != BluetoothGatt.GATT_SUCCESS) {
            lastError = "scrittura: stato ${st ?: "nessuna risposta"}${reasonOf(st ?: -1)}"
            return false
        }
        return true
    }

    /**
     * Scrive un comando e raccoglie i frame di notifica finché [terminal] è vero
     * su uno di essi, oppure finché c'è silenzio per [quietMs].
     */
    suspend fun transact(
        cmd: ByteArray,
        quietMs: Long = 2_000,
        overallMs: Long = 30_000,
        verbose: Boolean = true,
        terminal: (ByteArray) -> Boolean = { true },
    ): List<ByteArray> = txMutex.withLock {
        while (notifications.tryReceive().isSuccess) { /* scarta notifiche vecchie */ }
        val frames = mutableListOf<ByteArray>()
        if (verbose) log("→ ${hex(cmd)}")
        if (!write(cmd)) { log("✗ $lastError"); return@withLock frames }

        val deadline = System.currentTimeMillis() + overallMs
        while (System.currentTimeMillis() < deadline && isConnected) {
            val notif = withTimeoutOrNull(quietMs) { notifications.receive() } ?: break
            if (notif.isEmpty()) break // disconnessione
            for (f in splitFrames(notif)) {
                frames += f
                if (verbose) log("← ${hex(f)}")
                if (terminal(f)) return@withLock frames
            }
        }
        if (verbose && frames.isEmpty()) log("← (nessuna risposta)")
        frames
    }

    fun close() {
        isConnected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        fun hex(b: ByteArray, max: Int = 48): String {
            val s = b.take(max).joinToString("") { "%02x".format(it) }
            return if (b.size > max) "$s… (${b.size} B)" else s
        }

        fun reasonOf(status: Int): String = when (status) {
            5, 15 -> " = serve accoppiamento/cifratura"
            8 -> " = timeout del collegamento"
            19 -> " = chiuso dall'anello"
            22 -> " = chiuso dal telefono"
            62 -> " = connessione non stabilita"
            133 -> " = errore generico Android (riprovare)"
            -1 -> ""
            else -> ""
        }

        /** Divide una notifica in frame tag|len|payload concatenati. */
        fun splitFrames(data: ByteArray): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            var i = 0
            while (i + 2 <= data.size) {
                val len = data[i + 1].toInt() and 0xff
                val end = i + 2 + len
                if (end > data.size) {
                    out += data.copyOfRange(i, data.size)
                    break
                }
                out += data.copyOfRange(i, end)
                i = end
            }
            if (out.isEmpty() && data.isNotEmpty()) out += data
            return out
        }
    }
}
