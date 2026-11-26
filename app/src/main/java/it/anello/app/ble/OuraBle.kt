package it.anello.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trasporto BLE verso l'anello: connessione, notifiche, scrittura comandi e
 * raccolta delle risposte come coroutine. Un comando alla volta (Mutex).
 *
 * I callback BLE arrivano su thread di sistema: qui vengono convertiti in
 * Deferred/Channel per essere usati da coroutine normali.
 */
@SuppressLint("MissingPermission")
class OuraBle(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val notifications = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val txMutex = Mutex()

    private var connected: CompletableDeferred<Boolean>? = null
    private var servicesReady: CompletableDeferred<Boolean>? = null
    private var mtuReady: CompletableDeferred<Boolean>? = null
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var descriptorAck: CompletableDeferred<Boolean>? = null

    var lastError: String? = null
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected?.complete(true)
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                lastError = "disconnesso (status $status)"
                connected?.complete(false)
                servicesReady?.complete(false)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuReady?.complete(true)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(OuraProtocol.SERVICE)
            if (svc == null) {
                lastError = "servizio Oura non trovato"
                servicesReady?.complete(false)
                return
            }
            writeChar = svc.getCharacteristic(OuraProtocol.WRITE_CHAR)
            notifyChar = svc.getCharacteristic(OuraProtocol.NOTIFY_CHAR)
            servicesReady?.complete(writeChar != null && notifyChar != null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API < 33
        @Deprecated("deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { notifications.trySend(it.copyOf()) }
        }

        // API >= 33
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(value.copyOf())
        }
    }

    /** Connette, scopre i servizi e attiva le notifiche. Ritorna true se pronto. */
    suspend fun connect(device: BluetoothDevice): Boolean {
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        mtuReady = CompletableDeferred()

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }

        val ok = withTimeoutOrNull(20_000) { connected?.await() } ?: false
        if (!ok) { lastError = lastError ?: "connessione fallita"; return false }
        val svc = withTimeoutOrNull(20_000) { servicesReady?.await() } ?: false
        if (!svc) return false
        return enableNotifications()
    }

    private suspend fun enableNotifications(): Boolean {
        val g = gatt ?: return false
        val c = notifyChar ?: return false
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(OuraProtocol.CCCD) ?: return false
        descriptorAck = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        return withTimeoutOrNull(5_000) { descriptorAck?.await() } ?: false
    }

    /** Avvia il bonding se non già presente (necessario dopo SetAuthKey). */
    fun ensureBond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
    }

    private suspend fun write(cmd: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        writeAck = CompletableDeferred()
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        if (!ok) return false
        return withTimeoutOrNull(5_000) { writeAck?.await() } ?: false
    }

    /**
     * Scrive un comando e raccoglie i pacchetti di notifica finché [terminal] è vero
     * su uno di essi, oppure finché c'è silenzio per [quietMs]. Ogni notifica può
     * contenere più frame concatenati: vengono separati da [splitFrames].
     */
    suspend fun transact(
        cmd: ByteArray,
        quietMs: Long = 2_000,
        overallMs: Long = 30_000,
        terminal: (ByteArray) -> Boolean = { true },
    ): List<ByteArray> = txMutex.withLock {
        // svuota notifiche vecchie
        while (notifications.tryReceive().isSuccess) { /* drop */ }
        val frames = mutableListOf<ByteArray>()
        if (!write(cmd)) { lastError = "scrittura fallita"; return@withLock frames }

        val deadline = System.currentTimeMillis() + overallMs
        while (System.currentTimeMillis() < deadline) {
            val notif = withTimeoutOrNull(quietMs) { notifications.receive() } ?: break
            for (f in splitFrames(notif)) {
                frames += f
                if (terminal(f)) return@withLock frames
            }
        }
        frames
    }

    fun close() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    companion object {
        /** Divide una notifica in frame tag|len|payload concatenati. */
        fun splitFrames(data: ByteArray): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            var i = 0
            while (i + 2 <= data.size) {
                val len = data[i + 1].toInt() and 0xff
                val end = i + 2 + len
                if (end > data.size) { // frame troncato: restituisci il resto grezzo
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
