package it.anello.app.score

import it.anello.app.data.DailySummary
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Calibrazione incrociata tra due dispositivi indossati nelle stesse notti.
 *
 * Dispositivi diversi leggono valori diversi nella stessa notte (soprattutto l'HRV). Dalle notti
 * in comune si stima quanto il dispositivo B legge rispetto ad A: rapporto mediano per l'HRV
 * (differenza mediana in logaritmo) e differenza mediana per il battito a riposo.
 * Con la calibrazione una notte di B si può convertire nella scala di A, così la norma personale
 * continua quando si cambia dispositivo o quando A perde una notte.
 * Misura la concordanza tra dispositivi, non l'accuratezza: nessuno dei due è un riferimento.
 */
object Calibration {
    const val MIN_OVERLAP = 7

    data class Result(
        /** Notti in comune con HRV in entrambi. */
        val n: Int,
        /** Mediana di ln(HRV_A / HRV_B). */
        val lnRatio: Double,
        /** Dispersione robusta della differenza notte per notte (in ln). */
        val spread: Double,
        /** Mediana di FC_A − FC_B (bpm), se disponibile. */
        val rhrOffset: Double?,
    ) {
        val ready: Boolean get() = n >= MIN_OVERLAP
        /** Quanto B legge l'HRV rispetto ad A, in percento (negativo = B legge più basso). */
        val bVsAPercent: Double get() = (exp(-lnRatio) - 1) * 100
    }

    fun calibrate(a: Map<LocalDate, DailySummary>, b: Map<LocalDate, DailySummary>): Result {
        val diffs = ArrayList<Double>()
        val rhr = ArrayList<Double>()
        for ((date, da) in a) {
            val db = b[date] ?: continue
            if (!da.hasSleep || !db.hasSleep) continue
            val ha = da.avgHrv?.takeIf { it > 0 }
            val hb = db.avgHrv?.takeIf { it > 0 }
            if (ha != null && hb != null) diffs += ln(ha) - ln(hb)
            if (da.restingHr != null && db.restingHr != null) rhr += da.restingHr - db.restingHr
        }
        if (diffs.isEmpty()) return Result(0, 0.0, 0.0, null)
        val m = Insights.median(diffs)
        val mad = Insights.median(diffs.map { abs(it - m) })
        return Result(diffs.size, m, max(0.05, 1.4826 * mad), if (rhr.size >= MIN_OVERLAP) Insights.median(rhr) else null)
    }

    /** Converte una notte di B nella scala di A. */
    fun convert(night: DailySummary, cal: Result, fromLabel: String): DailySummary = night.copy(
        avgHrv = night.avgHrv?.let { it * exp(cal.lnRatio) },
        restingHr = night.restingHr?.let { r -> cal.rhrOffset?.let { r + it } ?: r },
        lowestHr = night.lowestHr?.let { r -> cal.rhrOffset?.let { r + it } ?: r },
        hrvSeries = night.hrvSeries.map { it.copy(v = it.v * exp(cal.lnRatio)) },
        skinTemp = null, // la temperatura non è confrontabile tra dispositivi diversi
        filledFrom = fromLabel,
    )

    /**
     * Riempie con le notti di B (convertite) i giorni in cui A non ha una notte.
     * Passi e calorie di A, se presenti, restano quelli di A.
     */
    fun fill(
        a: Map<LocalDate, DailySummary>, b: Map<LocalDate, DailySummary>, cal: Result, fromLabel: String,
    ): Map<LocalDate, DailySummary> {
        if (!cal.ready) return a
        val out = a.toMutableMap()
        for ((date, nb) in b) {
            if (!nb.hasSleep) continue
            val na = a[date]
            if (na != null && na.hasSleep) continue
            val conv = convert(nb, cal, fromLabel)
            out[date] = if (na == null) conv else conv.copy(steps = na.steps ?: conv.steps, activeKcal = na.activeKcal ?: conv.activeKcal)
        }
        return out
    }

    data class Agreement(val hrvA: Double, val hrvBConverted: Double, val z: Double, val agree: Boolean)

    /** Stanotte i due dispositivi concordano, tenuto conto della loro differenza abituale? */
    fun agreement(a: DailySummary, b: DailySummary, cal: Result): Agreement? {
        if (!cal.ready) return null
        val ha = a.avgHrv?.takeIf { it > 0 } ?: return null
        val hb = b.avgHrv?.takeIf { it > 0 } ?: return null
        val z = (ln(ha) - ln(hb) - cal.lnRatio) / cal.spread
        return Agreement(ha, hb * exp(cal.lnRatio), z, abs(z) <= 2.5)
    }
}
