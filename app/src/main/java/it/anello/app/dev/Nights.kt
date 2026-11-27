package it.anello.app.dev

import it.anello.app.data.DailySummary
import it.anello.app.data.Sample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Un intervallo di misura di un dispositivo: battito (se misurato), immobilità e, se ci sono, altri valori. */
data class Epoch(
    val tMs: Long,
    val hr: Int?,
    val still: Boolean,
    val hrv: Int? = null,
    val resp: Double? = null,
    val spo2: Int? = null,
)

data class SleepPeriod(val startMs: Long, val endMs: Long, val asleepMs: Long)

/**
 * Stima dei periodi di sonno per i dispositivi che non lo trasmettono (o non ne trasmettono le fasi).
 *
 * Due segnali insieme, come nel progetto RingLink (MIT) da cui viene l'idea: la mano ferma e il
 * battito almeno 5 bpm sotto il livello "da sveglio" della persona (70° percentile del battito
 * nelle 12 ore prima e dopo). Stare fermi sul divano non basta: serve anche il calo del battito.
 * Brevi risvegli (fino a 30 minuti) non interrompono la notte; periodi sotto i 90 minuti non contano.
 * È una stima: non distingue le fasi del sonno.
 */
object SleepEstimator {
    private const val HOUR = 3_600_000L
    private const val DIP_BPM = 5
    private const val MERGE_GAP = 30 * 60_000L
    private const val MIN_SESSION = 90 * 60_000L
    private const val MIN_SAMPLES = 20

    fun detect(epochs: List<Epoch>, epochMs: Long): List<SleepPeriod> {
        val usable = epochs.filter { it.hr != null }.sortedBy { it.tMs }
        if (usable.size < MIN_SAMPLES) return emptyList()

        val baselines = HashMap<Long, Int>()
        fun baseline(t: Long): Int? {
            val bucket = t / HOUR
            baselines[bucket]?.let { return if (it < 0) null else it }
            val centre = bucket * HOUR
            val near = usable.filter { abs(it.tMs - centre) <= 12 * HOUR }.mapNotNull { it.hr }.sorted()
            val b = if (near.size < MIN_SAMPLES) -1 else near[(near.size * 70 / 100).coerceAtMost(near.size - 1)]
            baselines[bucket] = b
            return if (b < 0) null else b
        }

        val out = ArrayList<SleepPeriod>()
        var start = -1L
        var last = 0L
        var count = 0
        fun close() {
            if (start >= 0) out += SleepPeriod(start, last + epochMs, count * epochMs)
            start = -1L
            count = 0
        }
        for (e in usable) {
            val b = baseline(e.tMs)
            val sleeping = b != null && e.still && (e.hr ?: Int.MAX_VALUE) <= b - DIP_BPM
            if (start >= 0 && e.tMs - last > MERGE_GAP) close()
            if (sleeping) {
                if (start < 0) start = e.tMs
                last = e.tMs
                count++
            }
        }
        close()
        return out.filter { it.endMs - it.startMs >= MIN_SESSION }
    }
}

/** Dalle misure a intervalli regolari alle notti: una notte (la più lunga) per giorno di risveglio. */
object DeviceNights {
    fun build(epochs: List<Epoch>, epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<DailySummary> {
        val sorted = epochs.sortedBy { it.tMs }
        return SleepEstimator.detect(sorted, epochMs).map { p ->
            val inside = sorted.filter { it.tMs >= p.startMs && it.tMs < p.endMs }
            val hr = inside.mapNotNull { e -> e.hr?.let { Sample(e.tMs, it.toDouble()) } }
            val hrv = inside.mapNotNull { e -> e.hrv?.let { Sample(e.tMs, it.toDouble()) } }
            val resp = inside.mapNotNull { it.resp }
            val spo2 = inside.mapNotNull { it.spo2 }
            val v = hr.map { it.v }
            // Minimo della media su circa 5 minuti.
            val w = (300_000L / epochMs).toInt().coerceIn(1, 4)
            val lowest = if (v.size >= w) (0..v.size - w).minOf { k -> v.subList(k, k + w).average() } else v.minOrNull()
            val inBed = ((p.endMs - p.startMs) / 60_000L).toInt()
            val asleep = (p.asleepMs / 60_000L).toInt().coerceAtMost(inBed)
            DailySummary(
                date = Instant.ofEpochMilli(p.endMs).atZone(zone).toLocalDate(),
                sleepStartMs = p.startMs,
                sleepEndMs = p.endMs,
                inBedMin = inBed,
                asleepMin = asleep,
                awakeMin = inBed - asleep,
                lowestHr = lowest,
                avgSleepHr = if (v.isEmpty()) null else v.average(),
                avgHrv = if (hrv.isEmpty()) null else hrv.map { it.v }.average(),
                restingHr = lowest,
                spo2 = if (spo2.isEmpty()) null else spo2.average(),
                respiratoryRate = if (resp.isEmpty()) null else resp.average(),
                hrSeries = DailySummary.bin(hr),
                hrvSeries = DailySummary.bin(hrv),
            )
        }.groupBy { it.date }.map { (_, l) -> l.maxBy { it.inBedMin } }
    }
}
