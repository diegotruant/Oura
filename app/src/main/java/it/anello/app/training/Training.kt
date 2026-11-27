package it.anello.app.training

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.pow

/** Riepilogo di un allenamento importato da un file FIT. */
data class Activity(
    val id: String,
    val day: LocalDate,
    val startUnix: Long,
    val seconds: Int,
    val meanPowerW: Double,
    val normalizedPowerW: Double?,
    val loadAu: Double,
    val workKj: Double,
    val wbalMinFraction: Double,
    val cpW: Double,
    val wPrimeJ: Double,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("day", day.toString()); put("startUnix", startUnix); put("seconds", seconds)
        put("meanPowerW", meanPowerW); putOpt("normalizedPowerW", normalizedPowerW); put("loadAu", loadAu)
        put("workKj", workKj); put("wbalMinFraction", wbalMinFraction); put("cpW", cpW); put("wPrimeJ", wPrimeJ)
    }

    companion object {
        fun fromJson(o: JSONObject) = Activity(
            o.getString("id"), LocalDate.parse(o.getString("day")), o.getLong("startUnix"), o.getInt("seconds"),
            o.getDouble("meanPowerW"), if (o.has("normalizedPowerW")) o.getDouble("normalizedPowerW") else null,
            o.getDouble("loadAu"), o.getDouble("workKj"), o.getDouble("wbalMinFraction"),
            o.getDouble("cpW"), o.getDouble("wPrimeJ"),
        )
    }
}

/**
 * Calcoli sull'allenamento, trasposti da research/apse/apse/wbal.py (modelli pubblicati):
 * potenza normalizzata (media mobile 30 s, quarta potenza), W′bal differenziale (Skiba 2015),
 * carico = ore × (NP/CP)² × 100, forma con medie esponenziali a 42 e 7 giorni.
 */
object Training {

    fun normalizedPower(power: DoubleArray): Double? {
        if (power.size < 30) return null
        var acc = 0.0
        for (i in 0 until 30) acc += power[i]
        var sum4 = (acc / 30).pow(4)
        var n = 1
        for (i in 30 until power.size) {
            acc += power[i] - power[i - 30]
            sum4 += (acc / 30).pow(4)
            n++
        }
        return (sum4 / n).pow(0.25)
    }

    /** Minimo di W′bal (J) con il modello differenziale. */
    fun wbalMin(power: DoubleArray, cp: Double, wPrime: Double): Double {
        var w = wPrime
        var minW = wPrime
        for (p in power) {
            w += if (p > cp) -(p - cp) else (cp - p) * (wPrime - w) / wPrime
            if (w < minW) minW = w
        }
        return minW
    }

    fun summarize(bytes: ByteArray, cp: Double, wPrime: Double, zone: ZoneId = ZoneId.systemDefault()): Activity {
        require(cp in 50.0..700.0) { "CP fuori intervallo 50-700 W" }
        require(wPrime in 2_000.0..60_000.0) { "W′ fuori intervallo 2-60 kJ" }
        val records = Fit.decode(bytes)
        if (records.isEmpty()) throw Fit.FitException("nessun record nel file")
        if (records.none { it.powerW != null }) throw Fit.FitException("nessun dato di potenza nel file")
        val (power, _) = Fit.powerSeries(records)
        val np = normalizedPower(power)
        val seconds = power.size
        val mean = power.sum() / seconds
        val intensity = (np ?: mean) / cp
        val start = records.minOf { it.timestamp } + Fit.FIT_EPOCH_UNIX
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Activity(
            id = id,
            day = Instant.ofEpochSecond(start).atZone(zone).toLocalDate(),
            startUnix = start,
            seconds = seconds,
            meanPowerW = mean,
            normalizedPowerW = np,
            loadAu = seconds / 3600.0 * intensity * intensity * 100,
            workKj = power.sum() / 1000,
            wbalMinFraction = wbalMin(power, cp, wPrime) / wPrime,
            cpW = cp,
            wPrimeJ = wPrime,
        )
    }

    fun dailyLoads(activities: List<Activity>): Map<LocalDate, Double> =
        activities.groupBy { it.day }.mapValues { (_, a) -> a.sumOf { it.loadAu } }

    data class Form(val date: LocalDate, val load: Double, val chronic: Double, val acute: Double) {
        /** Bilancio: positivo = fresco, negativo = affaticato. */
        val balance: Double get() = chronic - acute
    }

    /** Carico cronico (42 giorni) e acuto (7 giorni) dal primo allenamento fino a [until]. */
    fun form(loads: Map<LocalDate, Double>, until: LocalDate): List<Form> {
        val first = loads.keys.minOrNull() ?: return emptyList()
        val out = ArrayList<Form>()
        var c = 0.0
        var a = 0.0
        var d = first
        while (!d.isAfter(until)) {
            val l = loads[d] ?: 0.0
            c += (1 - exp(-1.0 / 42)) * (l - c)
            a += (1 - exp(-1.0 / 7)) * (l - a)
            out += Form(d, l, c, a)
            d = d.plusDays(1)
        }
        return out
    }
}

/** Allenamenti importati, in un file JSON nella memoria privata dell'app. */
class TrainingStore(context: Context) {
    private val file = File(context.filesDir, "activities.json")

    @Synchronized
    fun load(): List<Activity> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { Activity.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Aggiunge l'attività se non è già presente (stesso file = stesso id). Ritorna true se nuova. */
    @Synchronized
    fun add(activity: Activity): Boolean {
        val all = load()
        if (all.any { it.id == activity.id }) return false
        save(all + activity)
        return true
    }

    @Synchronized
    fun remove(id: String) = save(load().filterNot { it.id == id })

    private fun save(all: List<Activity>) {
        val arr = JSONArray()
        all.sortedBy { it.startUnix }.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "activities.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
