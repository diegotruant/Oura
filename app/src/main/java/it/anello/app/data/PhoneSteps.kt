package it.anello.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Contapassi del telefono: il sensore di sistema conta i passi dall'accensione, senza bisogno di
 * altre app. Lo si legge ogni tanto (all'apertura e a ogni sincronizzazione in background) e la
 * differenza dalla lettura precedente viene assegnata ai giorni trascorsi nel frattempo.
 * Conta solo i passi fatti con il telefono addosso.
 */
object PhoneSteps {
    private const val PREFS = "phone_steps"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasSensor(context: Context): Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Valore attuale del contatore (passi dall'accensione del telefono), null se non disponibile. */
    private suspend fun readCounter(context: Context): Long? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val result = CompletableDeferred<Long>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { result.complete(event.values[0].toLong()) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        return try {
            if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return null
            withTimeoutOrNull(4_000) { result.await() }
        } finally {
            sm.unregisterListener(listener)
        }
    }

    /**
     * Divide [steps] tra i giorni compresi tra [fromMs] e [toMs], in proporzione al tempo.
     * Le letture sono circa ogni ora, quindi quasi sempre tutto cade in un giorno solo.
     */
    fun split(steps: Long, fromMs: Long, toMs: Long, zone: ZoneId): Map<LocalDate, Long> {
        if (steps <= 0 || toMs <= fromMs) return emptyMap()
        val out = LinkedHashMap<LocalDate, Long>()
        var t = fromMs
        var left = steps
        while (t < toMs) {
            val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            val end = minOf(toMs, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val part = if (end >= toMs) left else Math.round(steps * (end - t).toDouble() / (toMs - fromMs)).coerceAtMost(left)
            if (part > 0) out[day] = part
            left -= part
            t = end
        }
        return out
    }

    /** Legge il contatore e aggiorna i totali per giorno. Non fa niente se è spento o manca il permesso. */
    suspend fun update(context: Context, zone: ZoneId = ZoneId.systemDefault()) {
        if (!isEnabled(context) || !hasPermission(context)) return
        val value = readCounter(context) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("value", -1)
        val lastTime = prefs.getLong("time", 0)
        val days = load(context).toMutableMap()
        // Dopo un riavvio il contatore riparte da zero: si prende la nuova base senza contare.
        // Lo stesso se è passato più di un giorno dall'ultima lettura: non si sa come dividere.
        if (last >= 0 && value >= last && now - lastTime < 26 * 3_600_000L) {
            for ((d, n) in split(value - last, lastTime, now, zone)) days[d] = (days[d] ?: 0) + n
        }
        val cutoff = LocalDate.now(zone).minusDays(120)
        val o = JSONObject()
        for ((d, n) in days) if (d >= cutoff) o.put(d.toString(), n)
        prefs.edit().putLong("value", value).putLong("time", now).putString("days", o.toString()).apply()
    }

    /** Passi per giorno contati dal telefono. */
    fun load(context: Context): Map<LocalDate, Long> = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("days", "{}") ?: "{}")
        o.keys().asSequence().associate { LocalDate.parse(it) to o.getLong(it) }
    }.getOrDefault(emptyMap())
}
