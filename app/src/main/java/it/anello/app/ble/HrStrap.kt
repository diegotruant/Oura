package it.anello.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * Fascia cardio Bluetooth con il profilo standard "Heart Rate" (servizio 0x180D, caratteristica
 * 0x2A37), pubblico e comune a Polar, Garmin, Wahoo e altre. Fornisce battito e intervalli RR.
 */
@SuppressLint("MissingPermission")
class HrStrap(private val context: Context) {

    data class Found(val name: String, val address: String, val rssi: Int)
    data class Measurement(val heartRate: Int, val rrMs: List<Double>)

    private val adapter by lazy { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    private var scanCb: ScanCallback? = null
    private var gatt: BluetoothGatt? = null

    fun startScan(onFound: (Found) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "Fascia cardio"
                onFound(Found(name, result.device.address, result.rssi))
            }
        }
        scanCb = cb
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb,
        )
    }

    fun stopScan() {
        scanCb?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCb = null
    }

    /** Si collega e inoltra ogni misura a [onData]; [onState] riceve "connesso", "pronto" o un errore. */
    fun connect(address: String, onState: (String) -> Unit, onData: (Measurement) -> Unit) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { onState("indirizzo non valido"); return }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    onState("connesso"); g.discoverServices()
                } else onState("disconnesso (stato $status)")
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                val cccd = c?.getDescriptor(CCCD)
                if (c == null || cccd == null) { onState("questa fascia non espone il profilo Heart Rate"); return }
                g.setCharacteristicNotification(c, true)
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                onState(if (status == BluetoothGatt.GATT_SUCCESS) "pronto" else "notifiche rifiutate (stato $status)")
            }

            @Deprecated("deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                c.value?.let { v -> parse(v)?.let(onData) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                parse(value)?.let(onData)
            }
        }
        gatt = device.connectGatt(context, false, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Decodifica "Heart Rate Measurement" (specifica Bluetooth SIG): byte 0 = flag;
         * bit0: battito a 16 bit; bit3: energia presente (2 byte); bit4: intervalli RR presenti,
         * ciascuno 16 bit little-endian in unità di 1/1024 di secondo.
         */
        fun parse(v: ByteArray): Measurement? {
            if (v.size < 2) return null
            val flags = v[0].toInt() and 0xff
            var i = 1
            val hr: Int
            if (flags and 0x01 != 0) {
                if (v.size < 3) return null
                hr = (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8); i = 3
            } else {
                hr = v[1].toInt() and 0xff; i = 2
            }
            if (flags and 0x08 != 0) i += 2
            val rr = ArrayList<Double>()
            if (flags and 0x10 != 0) {
                while (i + 1 < v.size) {
                    val raw = (v[i].toInt() and 0xff) or ((v[i + 1].toInt() and 0xff) shl 8)
                    rr += raw * 1000.0 / 1024.0
                    i += 2
                }
            }
            return Measurement(hr, rr)
        }
    }
}
