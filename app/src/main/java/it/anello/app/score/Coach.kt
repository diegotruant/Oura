package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.UserSettings
import it.anello.app.training.Training
import java.time.LocalDate

/**
 * Consiglio del giorno per l'allenamento. Parte dal Recupero e lo abbassa quando un altro segnale
 * dice di andarci piano: stato dell'HRV sulla settimana, segni vitali fuori norma, carico recente
 * molto sopra l'abituale, poco sonno. Ogni abbassamento porta con sé il motivo, così il consiglio
 * si può sempre contestare. Sono regole dichiarate, non un modello addestrato.
 */
object Coach {
    enum class Plan(val title: String) {
        REST("Riposo o recupero attivo"),
        EASY("Uscita leggera"),
        ENDURANCE("Fondo, senza intensità"),
        INTENSITY("Via libera all'intensità"),
    }

    data class Advice(val plan: Plan, val reasons: List<String>)

    fun advise(
        recovery: Int?,
        hrv: Deep.HrvKind?,
        vitalsOut: Int,
        acute: Double?,
        chronic: Double?,
        asleepMin: Int?,
        sleepGoalMin: Int,
    ): Advice? {
        if (recovery == null && hrv == null) return null
        var plan = Plan.INTENSITY
        val reasons = ArrayList<String>()
        fun cap(p: Plan, why: String) {
            if (p.ordinal <= plan.ordinal) { if (p.ordinal < plan.ordinal) plan = p; reasons += why }
        }
        if (recovery != null) when {
            recovery < 35 -> cap(Plan.REST, "Recupero $recovery: molto basso")
            recovery < 55 -> cap(Plan.EASY, "Recupero $recovery: basso")
            recovery < 70 -> cap(Plan.ENDURANCE, "Recupero $recovery: medio")
            else -> reasons += "Recupero $recovery: buono"
        }
        when (hrv) {
            Deep.HrvKind.STRAIN -> cap(Plan.EASY, "HRV della settimana giù e battito su: carico non smaltito")
            Deep.HrvKind.SATURATION -> cap(Plan.EASY, "HRV e battito della settimana entrambi bassi: possibile fatica profonda")
            Deep.HrvKind.LOW -> cap(Plan.ENDURANCE, "HRV della settimana sotto la norma")
            Deep.HrvKind.UNSETTLED -> cap(Plan.ENDURANCE, "HRV instabile negli ultimi giorni")
            else -> {}
        }
        if (vitalsOut >= 2) cap(Plan.REST, "$vitalsOut segni vitali fuori norma: possibile malattia in arrivo")
        else if (vitalsOut == 1) cap(Plan.ENDURANCE, "Un segno vitale fuori norma")
        if (acute != null && chronic != null && chronic >= 20 && acute > 1.3 * chronic) {
            cap(Plan.ENDURANCE, "Carico degli ultimi 7 giorni del %.0f%% sopra il tuo abituale".format((acute / chronic - 1) * 100))
        }
        if (asleepMin != null && asleepMin > 0 && asleepMin < sleepGoalMin - 90) {
            cap(Plan.ENDURANCE, "Stanotte %dh %02dm di sonno, molto sotto l'obiettivo".format(asleepMin / 60, asleepMin % 60))
        }
        return Advice(plan, reasons)
    }

    /** Consiglio per [day] dai dati salvati; [loads] = carico di allenamento per giorno (può essere vuoto). */
    fun forDay(history: List<DailySummary>, day: DailySummary, settings: UserSettings, loads: Map<LocalDate, Double>): Advice? {
        if (!day.hasSleep) return null
        val recovery = Scores.compute(day, history, settings).readiness?.value
        val form = Training.form(loads, day.date.minusDays(1)).lastOrNull()
        return advise(
            recovery, Deep.hrvState(history, day.date)?.kind, Deep.health(history, day)?.out ?: 0,
            form?.acute, form?.chronic, day.asleepMin, settings.sleepGoalMin,
        )
    }
}
