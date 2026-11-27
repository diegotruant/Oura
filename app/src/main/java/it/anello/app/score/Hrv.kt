package it.anello.app.score

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Calcoli sugli intervalli tra battiti (RR), con le stesse regole di APSE (engines.autonomic). */
object Hrv {
    data class Result(val rmssd: Double?, val meanHr: Double?, val coverage: Double, val pairs: Int)

    const val MIN_PAIRS = 20
    const val MIN_COVERAGE = 0.8

    /**
     * RMSSD da intervalli RR consecutivi in millisecondi. Una coppia è valida se entrambi gli
     * intervalli sono tra 300 e 2000 ms e differiscono al massimo del 20% (battiti persi o
     * extrasistoli vengono scartati, senza "ponti"). Servono almeno [MIN_PAIRS] coppie valide
     * e l'80% di coppie buone, altrimenti la misura non è affidabile e l'RMSSD è nullo.
     */
    fun rmssd(rr: List<Double>): Result {
        fun valid(x: Double) = x.isFinite() && x in 300.0..2000.0
        var sum = 0.0
        var n = 0
        for (i in 0 until rr.size - 1) {
            val a = rr[i]; val b = rr[i + 1]
            if (valid(a) && valid(b) && abs(b - a) / max(a, b) <= 0.20) { sum += (b - a) * (b - a); n++ }
        }
        val coverage = n.toDouble() / max(1, rr.size - 1)
        val good = rr.filter { valid(it) }
        val hr = if (good.isEmpty()) null else 60_000.0 / good.average()
        return Result(if (n >= MIN_PAIRS && coverage >= MIN_COVERAGE) sqrt(sum / n) else null, hr, coverage, n)
    }

    /** Correlazione di Pearson; null con meno di 3 coppie o varianza nulla. */
    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average(); val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val syy = y.sumOf { (it - my) * (it - my) }
        if (sxx <= 0 || syy <= 0) return null
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sqrt(sxx * syy)
    }
}
