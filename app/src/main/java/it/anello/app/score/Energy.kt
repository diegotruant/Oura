package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.UserSettings

/**
 * Calorie della giornata, con formule pubbliche e dichiarate.
 *
 * - A riposo: equazione di Mifflin-St Jeor (1990) da peso, altezza, età e sesso.
 * - Attive: se la fonte le misura si usano quelle; altrimenti si stimano dai passi (costo netto
 *   del cammino circa 0,5 kcal per kg per km, con passo lungo 0,415 × altezza) più il lavoro
 *   degli allenamenti in bici (1 kJ di lavoro meccanico ≈ 1 kcal spesa, con un rendimento
 *   intorno al 24%).
 */
object Energy {
    /** Metabolismo a riposo in kcal al giorno; null se mancano peso, altezza o anno di nascita. */
    fun restingPerDay(s: UserSettings, year: Int): Double? {
        if (s.weightKg <= 0 || s.heightCm <= 0 || s.birthYear <= 0) return null
        val age = (year - s.birthYear).coerceIn(10, 100)
        val sex = when (s.sex) { "M" -> 5.0; "F" -> -161.0; else -> -78.0 }
        return 10 * s.weightKg + 6.25 * s.heightCm - 5 * age + sex
    }

    /** Calorie nette del cammino per [steps] passi; null senza il peso. */
    fun fromSteps(steps: Long, s: UserSettings): Double? {
        if (s.weightKg <= 0) return null
        val stride = if (s.heightCm > 0) 0.415 * s.heightCm / 100 else 0.75
        return steps * stride / 1000 * s.weightKg * 0.5
    }

    data class Day(
        val active: Double?,
        /** true se le calorie attive sono stimate dai passi e dalla bici invece che misurate dalla fonte. */
        val estimated: Boolean,
        /** A riposo fino a questo momento della giornata (tutto il giorno per i giorni passati). */
        val resting: Double?,
    ) {
        val total: Double? get() = if (active != null && resting != null) active + resting else null
    }

    /**
     * [rideKj]: lavoro degli allenamenti in bici del giorno. [dayFraction]: quanta parte del giorno
     * è trascorsa (1 per i giorni passati).
     */
    fun day(d: DailySummary, s: UserSettings, rideKj: Double, dayFraction: Double = 1.0): Day {
        val resting = restingPerDay(s, d.date.year)?.let { it * dayFraction.coerceIn(0.0, 1.0) }
        val measured = d.activeKcal
        if (measured != null) return Day(measured, false, resting)
        val steps = d.steps?.let { fromSteps(it, s) }
        val active = if (steps == null && rideKj <= 0) null else (steps ?: 0.0) + rideKj
        return Day(active, true, resting)
    }
}
