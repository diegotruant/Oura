package it.anello.app.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.anello.app.MorningNotifier
import java.util.concurrent.TimeUnit

/**
 * Sincronizzazione automatica in background: circa ogni ora Android avvia questo
 * lavoro, che si collega all'anello associato e scarica i nuovi eventi.
 * Se manca qualcosa (Bluetooth spento, permessi, chiave) non fa nulla.
 */
class RingSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = RingStore(applicationContext)
        if (!store.autoSync) return Result.success()
        if (!canUseBluetooth(applicationContext)) return Result.success()
        // Tutti gli anelli associati, uno alla volta. In caso di errore riproverà al prossimo giro.
        val ble = RingBle(applicationContext, store)
        for (address in store.pairedRings()) {
            ble.sync(address, log = {}, skipIfBusy = true)
        }
        runCatching { MorningNotifier.maybeNotify(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val NAME = "anello-sync"

        fun canUseBluetooth(ctx: Context): Boolean {
            val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
            else Manifest.permission.BLUETOOTH
            if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) return false
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            return adapter?.isEnabled == true
        }

        /** Attiva o disattiva la sincronizzazione periodica (circa ogni ora). */
        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<RingSyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
