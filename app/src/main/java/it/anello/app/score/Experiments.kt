package it.anello.app.score

import it.anello.app.data.DailySummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln

/**
 * Esperimento personale a fasi alterne: fase A (con la modifica) e fase B (senza), ciascuna di
 * [phaseDays] giorni, ripetute [cycles] volte (A-B oppure A-B-A-B). Alternare le fasi riduce
 * l'effetto di ciò che cambia nel tempo per conto suo (stagione, forma). Il primo giorno di ogni
 * fase è escluso dall'analisi (transizione). Come per il diario, il giorno D vale per la notte
 * che finisce in D+1.
 */
data class Experiment(
    val id: String,
    val name: String,
    val start: LocalDate,
    val phaseDays: Int,
    val cycles: Int = 1,
)

object Experiments {
    enum class Phase { NOT_STARTED, A, B, DONE }

    fun totalDays(e: Experiment) = 2 * e.phaseDays * e.cycles
    fun end(e: Experiment): LocalDate = e.start.plusDays(totalDays(e).toLong() - 1)

    fun phase(e: Experiment, date: LocalDate): Phase {
        val k = ChronoUnit.DAYS.between(e.start, date)
        if (k < 0) return Phase.NOT_STARTED
        if (k >= totalDays(e)) return Phase.DONE
        return if ((k / e.phaseDays) % 2 == 0L) Phase.A else Phase.B
    }

    /** Giorno all'interno della fase corrente, da 1 a phaseDays. */
    fun dayInPhase(e: Experiment, date: LocalDate): Int =
        (ChronoUnit.DAYS.between(e.start, date) % e.phaseDays).toInt() + 1

    data class Outcome(val nA: Int, val nB: Int, val comparison: Insights.Comparison?)

    /** Effetto sull'HRV notturna: notti dopo i giorni della fase A contro quelle della fase B. */
    fun outcome(e: Experiment, history: List<DailySummary>): Outcome {
        val byDate = history.associateBy { it.date }
        val a = ArrayList<Double>()
        val b = ArrayList<Double>()
        var d = e.start
        val last = end(e)
        while (!d.isAfter(last)) {
            if (dayInPhase(e, d) > 1) { // esclude il giorno di transizione
                byDate[d.plusDays(1)]?.avgHrv?.takeIf { it > 0 }?.let { h ->
                    if (phase(e, d) == Phase.A) a += ln(h) else b += ln(h)
                }
            }
            d = d.plusDays(1)
        }
        return Outcome(a.size, b.size, Insights.compareLn(a, b))
    }
}
