package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.UserSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un contributo al punteggio, mostrato nel dettaglio. */
data class Contributor(val label: String, val value: String, val points: Int, val max: Int)

data class Score(
    /** null = non calcolabile (il motivo è in [note]). */
    val value: Int?,
    val contributors: List<Contributor>,
    /** Nota da mostrare sotto i contributi (es. calo dell'HRV in corso). */
    val note: String? = null,
    /** true se l'utente può confermare un nuovo livello normale dell'HRV. */
    val canRebaseline: Boolean = false,
    /** true se la nota è un avviso (mostrata in evidenza). */
    val noteIsWarning: Boolean = false,
    /** Margine in punti dovuto all'incertezza della norma personale. */
    val margin: Double? = null,
)

data class DayScores(val sleep: Score?, val readiness: Score?, val activity: Score?)

/**
 * Punteggi calcolati in locale con regole trasparenti e confronto con la TUA media personale
 * (baseline degli ultimi 14 giorni). Non sono gli algoritmi di Oura, che sono proprietari.
 */
object Scores {

    fun compute(day: DailySummary, history: List<DailySummary>, settings: UserSettings): DayScores {
        val sleep = sleepScore(day, settings)
        return DayScores(
            sleep = sleep,
            readiness = recoveryScore(day, history, settings),
            activity = activityScore(day, settings),
        )
    }

    /**
     * Recupero APSE 1.2 (vedi Apse.kt): HRV rispetto alla tua norma (metà del punteggio),
     * pressione del sonno della notte (un quarto) e debito di sonno recente (un quarto).
     */
    fun recoveryScore(day: DailySummary, history: List<DailySummary>, settings: UserSettings): Score? {
        val apse = Apse.cached(history, settings.sleepGoalMin / 60.0, settings.rebaselineDays())[day.date] ?: return null
        val hrv = day.avgHrv
        val ref = apse.referenceMs
        val stateText = when (apse.status) {
            Apse.Status.ABOVE -> "sopra la norma"
            Apse.Status.NORMAL -> "nella norma"
            Apse.Status.LOW -> "sotto la norma"
            Apse.Status.ALERT, Apse.Status.PERSISTENT_CHANGE ->
                if (apse.releaseProgress > 0) "in ripresa (${apse.releaseProgress}/${Apse.RELEASE_DAYS})"
                else if (apse.status == Apse.Status.ALERT) "calo in corso" else "calo persistente"
            else -> ""
        }
        val inAlert = apse.status == Apse.Status.ALERT || apse.status == Apse.Status.PERSISTENT_CHANGE
        val note = when {
            inAlert && apse.releaseProgress > 0 ->
                "La tua HRV è tornata nella norma: l'episodio di calo si chiude dopo ${Apse.RELEASE_DAYS} notti " +
                    "di fila nella norma (finora ${apse.releaseProgress}). Fino ad allora la norma resta quella " +
                    "di prima del calo."
            apse.status == Apse.Status.PERSISTENT_CHANGE ->
                "La tua HRV è sotto la norma da ${apse.episodeDays} giorni. Se c'è un motivo benigno " +
                    "(nuovo farmaco, cambio di stagione, anello diverso) puoi confermarlo come nuovo livello normale."
            apse.status == Apse.Status.ALERT ->
                "HRV sotto la tua norma da ${apse.episodeDays} giorni: possibile affaticamento, malattia o stress. " +
                    "La norma resta quella di prima finché il calo non rientra."
            hrv == null -> "HRV non disponibile per questa notte: il Recupero non si può calcolare."
            apse.referenceN < Apse.MIN_RECOVERY_N ->
                "Servono almeno ${Apse.MIN_RECOVERY_N} notti con HRV per stabilire la tua norma (finora ${apse.referenceN})."
            apse.referenceProvisional ->
                "Norma provvisoria su ${apse.referenceN} notti: diventa stabile dopo ${Apse.FULL_REFERENCE_N} notti."
            else -> null
        }
        val recovery = apse.recovery
            ?: return Score(null, emptyList(), note ?: "Dati insufficienti per il Recupero.")
        val c = listOf(
            Contributor(
                "HRV rispetto alla tua norma",
                listOfNotNull(hrv?.let { "${it.roundToInt()} ms" }, ref?.let { "norma ${it.roundToInt()}" }, stateText.ifEmpty { null })
                    .joinToString(" · "),
                ((apse.autonomicScore ?: 0.0) / 2).roundToInt(), 50,
            ),
            Contributor("Sonno della notte", fmtMin(day.asleepMin), ((apse.sleepPressureScore ?: 0.0) / 4).roundToInt(), 25),
            Contributor(
                "Debito di sonno",
                apse.sleepDebtHours?.let { if (it < 0.05) "nessuno" else "%.1f h/notte".format(it) } ?: "—",
                ((apse.sleepDebtScore ?: 0.0) / 4).roundToInt(), 25,
            ),
        )
        return Score(recovery.roundToInt().coerceIn(0, 100), c, note,
            canRebaseline = apse.status == Apse.Status.PERSISTENT_CHANGE,
            noteIsWarning = inAlert && apse.releaseProgress == 0,
            margin = apse.recoveryMargin)
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


    fun fmtMin(min: Int): String = "${min / 60}h ${"%02d".format(min % 60)}m"
}
