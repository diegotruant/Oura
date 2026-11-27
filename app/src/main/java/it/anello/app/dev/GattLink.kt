package it.anello.app.dev

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Collegamento Bluetooth generico: connessione, elenco dei servizi, lettura, scrittura e notifiche
 * di qualunque caratteristica. Un'operazione alla volta.
 */
@SuppressLint("MissingPermission")
class GattLink(private val context: Context, private val log: (String) -> Unit = {}) {
    private var gatt: BluetoothGatt? = null

    /** Notifiche in arrivo: caratteristica e valore. Un valore vuoto segnala la disconnessione. */
    private val incoming = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)

    private var connected: CompletableDeferred<Int>? = null
    private var servicesReady: CompletableDeferred<Boolean>? = null
    private var mtuReady: CompletableDeferred<Int>? = null
    private var writeAck: CompletableDeferred<Int>? = null
    private var descriptorAck: CompletableDeferred<Int>? = null
    private var readResult: CompletableDeferred<ByteArray?>? = null

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
                lastError = "collegamento chiuso (stato $status)"
                connected?.complete(if (status == 0) -1 else status)
                servicesReady?.complete(false)
                writeAck?.complete(-1)
                descriptorAck?.complete(-1)
                mtuReady?.complete(-1)
                readResult?.complete(null)
                incoming.trySend(Pair(NONE, ByteArray(0)))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesReady?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { mtuReady?.complete(mtu) }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descriptorAck?.complete(status) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeAck?.complete(status) }

        @Deprecated("deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            readResult?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readResult?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null)
        }

        @Deprecated("deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { incoming.trySend(Pair(c.uuid, it.copyOf())) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(Pair(c.uuid, value.copyOf()))
        }
    }

    /** Connette (con alcuni tentativi) e scopre i servizi. */
    suspend fun connect(device: BluetoothDevice, attempts: Int = 2): Boolean {
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
        if (withTimeoutOrNull(15_000) { connected?.await() } != 0) {
            lastError = lastError ?: "nessuna risposta entro 15 s"
            log("Connessione: $lastError")
            return false
        }
        gatt?.discoverServices()
        if (withTimeoutOrNull(15_000) { servicesReady?.await() } != true) {
            lastError = lastError ?: "ricerca servizi non riuscita"
            log("Servizi: $lastError")
            return false
        }
        mtuReady = CompletableDeferred()
        gatt?.requestMtu(247)
        withTimeoutOrNull(3_000) { mtuReady?.await() }
        return true
    }

    fun services(): List<BluetoothGattService> = gatt?.services ?: emptyList()

    fun has(service: UUID): Boolean = gatt?.getService(service) != null

    private fun find(service: UUID, char: UUID): BluetoothGattCharacteristic? = gatt?.getService(service)?.getCharacteristic(char)

    suspend fun read(service: UUID, char: UUID): ByteArray? {
        val g = gatt ?: return null
        val c = find(service, char) ?: return null
        readResult = CompletableDeferred()
        if (!g.readCharacteristic(c)) return null
        return withTimeoutOrNull(5_000) { readResult?.await() }
    }

    /** Attiva notifiche (o indicazioni) su una caratteristica. */
    suspend fun subscribe(service: UUID, char: UUID): Boolean {
        val g = gatt ?: return false
        val c = find(service, char) ?: return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        g.setCharacteristicNotification(c, true)
        val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        descriptorAck = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        val status = withTimeoutOrNull(8_000) { descriptorAck?.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            lastError = "notifiche: stato ${status ?: "nessuna risposta"}"
            return false
        }
        return true
    }

    /** Scrive un valore; con conferma se la caratteristica la prevede, altrimenti senza. */
    suspend fun write(service: UUID, char: UUID, bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = find(service, char) ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writeAck = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
        if (!started) { lastError = "scrittura non avviata"; return false }
        val st = withTimeoutOrNull(5_000) { writeAck?.await() }
        if (st != BluetoothGatt.GATT_SUCCESS) {
            lastError = "scrittura: stato ${st ?: "nessuna risposta"}"
            return false
        }
        return true
    }

    /** Prossima notifica entro [timeoutMs]; null se non arriva niente o il collegamento è caduto. */
    suspend fun next(timeoutMs: Long): Pair<UUID, ByteArray>? {
        val n = withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: return null
        return if (n.second.isEmpty()) null else n
    }

    /** Scarta le notifiche già arrivate. */
    fun drain() {
        while (incoming.tryReceive().isSuccess) { /* scarta */ }
    }

    fun close() {
        isConnected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val NONE = UUID(0L, 0L)
    }
}
