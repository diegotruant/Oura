package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.UserSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un contributo al punteggio, mostrato nel dettaglio. */
data class Contributor(val label: String, val value: String, val points: Int, val max: Int)

data class Score(val value: Int, val contributors: List<Contributor>)

data class DayScores(val sleep: Score?, val readiness: Score?, val activity: Score?)

/**
 * Punteggi calcolati in locale con regole trasparenti e confronto con la TUA media personale
 * (baseline degli ultimi 14 giorni). Non sono gli algoritmi di Oura, che sono proprietari.
 */
object Scores {

    fun compute(day: DailySummary, history: List<DailySummary>, settings: UserSettings): DayScores {
        val past = history.filter { it.date < day.date && it.date >= day.date.minusDays(14) }
        val sleep = sleepScore(day, settings)
        return DayScores(
            sleep = sleep,
            readiness = readinessScore(day, past, sleep, settings),
            activity = activityScore(day, settings),
        )
    }

    fun sleepScore(d: DailySummary, s: UserSettings): Score? {
        if (!d.hasSleep) return null
        val c = mutableListOf<Contributor>()

        // Durata: pieno punteggio all'obiettivo, a zero sotto il 50%.
        val durRatio = d.asleepMin.toDouble() / s.sleepGoalMin
        c += Contributor("Durata", fmtMin(d.asleepMin), lin(durRatio, 0.5, 1.0, 35), 35)

        // Efficienza: tempo dormito / tempo a letto. 90%+ è ottimo, 70% è scarso.
        val eff = if (d.inBedMin > 0) d.asleepMin.toDouble() / d.inBedMin else 0.0
        c += Contributor("Efficienza", "${(eff * 100).roundToInt()}%", lin(eff, 0.70, 0.90, 20), 20)

        val hasStages = d.deepMin + d.remMin > 0
        if (hasStages) {
            // Sonno profondo: ideale ~15-25% del sonno totale.
            val deepPct = d.deepMin.toDouble() / d.asleepMin
            c += Contributor("Profondo", "${fmtMin(d.deepMin)} (${(deepPct * 100).roundToInt()}%)", band(deepPct, 0.15, 0.25, 0.05, 15), 15)
            // REM: ideale ~20-25%.
            val remPct = d.remMin.toDouble() / d.asleepMin
            c += Contributor("REM", "${fmtMin(d.remMin)} (${(remPct * 100).roundToInt()}%)", band(remPct, 0.20, 0.25, 0.08, 15), 15)
        } else {
            c += Contributor("Fasi", "non disponibili", 22, 30)
        }

        // Tempo sveglio a letto: <20 min ottimo, >90 min scarso.
        c += Contributor("Risvegli", fmtMin(d.awakeMin), linInv(d.awakeMin.toDouble(), 20.0, 90.0, 15), 15)

        return Score(c.sumOf { it.points }.coerceIn(0, 100), c)
    }

    fun readinessScore(d: DailySummary, past: List<DailySummary>, sleep: Score?, s: UserSettings): Score? {
        if (!d.hasSleep) return null
        val c = mutableListOf<Contributor>()

        val hrvBase = past.mapNotNull { it.avgHrv }.median()
        if (d.avgHrv != null && hrvBase != null && past.count { it.avgHrv != null } >= 3) {
            // HRV sopra la media = recupero migliore. -25% dalla media → 0 punti.
            val rel = d.avgHrv / hrvBase
            c += Contributor("HRV", "${d.avgHrv.roundToInt()} ms (media ${hrvBase.roundToInt()})", lin(rel, 0.75, 1.0, 35), 35)
        } else {
            c += Contributor("HRV", d.avgHrv?.let { "${it.roundToInt()} ms (servono più notti)" } ?: "non disponibile", 25, 35)
        }

        val rhrBase = past.mapNotNull { it.restingHr }.median()
        if (d.restingHr != null && rhrBase != null && past.count { it.restingHr != null } >= 3) {
            // FC a riposo più alta della media = stress/affaticamento. +8 bpm → 0 punti.
            val delta = d.restingHr - rhrBase
            c += Contributor("FC a riposo", "${d.restingHr.roundToInt()} bpm (media ${rhrBase.roundToInt()})", linInv(delta, 0.0, 8.0, 30), 30)
        } else {
            c += Contributor("FC a riposo", d.restingHr?.let { "${it.roundToInt()} bpm (servono più notti)" } ?: "non disponibile", 22, 30)
        }

        c += Contributor("Sonno della notte", "${sleep?.value ?: "-"}", ((sleep?.value ?: 60) * 25 / 100), 25)

        // Debito di sonno: media delle ultime notti rispetto all'obiettivo.
        val recent = past.filter { it.hasSleep }.takeLast(5).map { it.asleepMin }
        if (recent.isNotEmpty()) {
            val ratio = recent.average() / s.sleepGoalMin
            c += Contributor("Equilibrio sonno", "media ${fmtMin(recent.average().roundToInt())}", lin(ratio, 0.7, 0.95, 10), 10)
        } else {
            c += Contributor("Equilibrio sonno", "servono più notti", 7, 10)
        }

        return Score(c.sumOf { it.points }.coerceIn(0, 100), c)
    }

    fun activityScore(d: DailySummary, s: UserSettings): Score? {
        val steps = d.steps ?: return null
        val c = mutableListOf<Contributor>()
        val ratio = steps.toDouble() / s.stepsGoal
        c += Contributor("Passi", "%,d / %,d".format(steps, s.stepsGoal), lin(ratio, 0.2, 1.0, 80), 80)
        val kcal = d.activeKcal
        if (kcal != null) {
            c += Contributor("Calorie attive", "${kcal.roundToInt()} kcal", lin(kcal, 100.0, 450.0, 20), 20)
        } else {
            c += Contributor("Calorie attive", "non disponibili", lin(ratio, 0.2, 1.0, 20), 20)
        }
        return Score(c.sumOf { it.points }.coerceIn(0, 100), c)
    }

    // --- funzioni di supporto ---

    /** 0 punti a [lo], pieni a [hi], lineare in mezzo. */
    private fun lin(x: Double, lo: Double, hi: Double, max: Int): Int =
        (((x - lo) / (hi - lo)).coerceIn(0.0, 1.0) * max).roundToInt()

    /** Inverso: pieni a [lo] o meno, 0 a [hi] o più. */
    private fun linInv(x: Double, lo: Double, hi: Double, max: Int): Int =
        (((hi - x) / (hi - lo)).coerceIn(0.0, 1.0) * max).roundToInt()

    /** Pieni dentro [lo, hi], scendono a zero allontanandosi di [falloff]. */
    private fun band(x: Double, lo: Double, hi: Double, falloff: Double, max: Int): Int {
        val dist = when {
            x < lo -> lo - x
            x > hi -> x - hi
            else -> 0.0
        }
        return ((1 - abs(dist) / falloff).coerceIn(0.0, 1.0) * max).roundToInt()
    }

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val s = sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun fmtMin(min: Int): String = "${min / 60}h ${"%02d".format(min % 60)}m"
}
