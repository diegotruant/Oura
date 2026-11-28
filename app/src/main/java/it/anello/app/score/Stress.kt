package it.anello.app.score

/**
 * Stress durante il giorno, dai battiti e dal movimento registrati dall'anello da svegli.
 *
 * Idea (metodo del "battito aggiuntivo", Myrtek 2004): il battito sale per due motivi, il movimento
 * e l'attivazione del sistema nervoso (tensione, impegno mentale, emozioni). Se il battito è alto
 * mentre il corpo è fermo, la parte in più non è spiegata dal movimento.
 *
 * Ogni finestra di 15 minuti viene confrontata con il battito minimo abituale della notte:
 * - in movimento: l'aumento è spiegato dall'attività;
 * - fermo e battito almeno il 40% sopra il minimo notturno: tensione;
 * - fermo e battito entro il 15% dal minimo notturno: calma;
 * - in mezzo: impegno normale.
 * Le soglie sono indicative e uguali per tutti in proporzione: non sono una misura clinica.
 */
object Stress {
    enum class Zone { CALM, ENGAGED, TENSE, ACTIVE }

    data class Window(val tMs: Long, val hr: Double, val motion: Double, val zone: Zone)

    data class Summary(val calmMin: Int, val engagedMin: Int, val tenseMin: Int, val activeMin: Int) {
        val measuredMin: Int get() = calmMin + engagedMin + tenseMin + activeMin
    }

    const val WINDOW_MS = 900_000L
    const val MIN_BEATS = 30
    const val TENSE_RATIO = 1.40
    const val CALM_RATIO = 1.15
    const val MOVING = 0.75
    const val SOME_MOTION = 0.5

    fun zone(hr: Double, restHr: Double, motion: Double): Zone {
        val ratio = hr / restHr
        return when {
            motion >= MOVING -> Zone.ACTIVE
            ratio >= TENSE_RATIO -> if (motion < SOME_MOTION) Zone.TENSE else Zone.ACTIVE
            ratio <= CALM_RATIO -> Zone.CALM
            else -> Zone.ENGAGED
        }
    }

    /**
     * [beats]: battiti validi (orario in ms, intervallo tra battiti in ms). [motion]: (orario, secondi
     * di movimento) per ogni rilevazione. [restHr]: battito minimo abituale della notte.
     * [asleep]: intervalli di sonno, esclusi. Una finestra conta solo con almeno [MIN_BEATS] battiti.
     */
    fun windows(
        beats: List<Pair<Long, Int>>,
        motion: List<Pair<Long, Int>>,
        restHr: Double,
        asleep: List<LongRange> = emptyList(),
    ): List<Window> {
        if (restHr <= 0) return emptyList()
        val moving = motion.groupBy { it.first / WINDOW_MS }.mapValues { (_, l) -> l.sumOf { it.second } }
        return beats.filter { it.second in 300..2000 }.groupBy { it.first / WINDOW_MS }.toSortedMap().mapNotNull { (w, l) ->
            if (l.size < MIN_BEATS) return@mapNotNull null
            val t = w * WINDOW_MS
            if (asleep.any { t + WINDOW_MS > it.first && t < it.last }) return@mapNotNull null
            val hr = 60_000.0 / Insights.median(l.map { it.second.toDouble() })
            val m = ((moving[w] ?: 0) / (WINDOW_MS / 1000.0)).coerceIn(0.0, 1.0)
            Window(t, hr, m, zone(hr, restHr, m))
        }
    }

    fun summary(windows: List<Window>): Summary {
        val min = (WINDOW_MS / 60_000).toInt()
        fun n(z: Zone) = windows.count { it.zone == z } * min
        return Summary(n(Zone.CALM), n(Zone.ENGAGED), n(Zone.TENSE), n(Zone.ACTIVE))
    }
}
