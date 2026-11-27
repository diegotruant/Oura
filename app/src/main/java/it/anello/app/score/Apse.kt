package it.anello.app.score

import it.anello.app.data.DailySummary
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Recupero secondo APSE 1.2 (research/apse/apse/reference_model.py), portato riga per riga.
 * Un test confronta i valori con quelli generati dal codice Python: se cambi qualcosa qui,
 * cambia anche il modello Python e rigenera i valori attesi (ApseParityTest).
 *
 * - Riferimento HRV = mediana di ln(RMSSD) negli ultimi 60 giorni, esclusi gli ultimi 7.
 * - Un calo brusco (>20%) o lento (media 7 giorni sotto 1 SD) per 3 giorni apre un episodio;
 *   i giorni dell'episodio non entrano nel riferimento, che viene congelato.
 * - Dopo 28 giorni l'episodio diventa "cambiamento persistente": solo una conferma esplicita
 *   (giorni di ricalibrazione) riparte da zero.
 * - Sonno: processo S giornaliero + debito di sonno medio pesato su 7 notti.
 *
 * Ogni archivio (app Oura via Health Connect, anello diretto) ha la sua storia: è il
 * "dispositivo" di APSE. Parametri illustrativi, non validati su dati reali.
 */
object Apse {
    const val WINDOW_DAYS = 60
    const val EXCLUDE_DAYS = 7
    const val FULL_REFERENCE_N = 14
    const val MIN_RECOVERY_N = 4
    const val SWC_FACTOR = .5
    const val SWC_FLOOR = .05
    const val ONSET_FACTOR = 1.0
    val ABRUPT_DROP = ln(.8)
    const val ALERT_DAYS = 3
    const val RELEASE_DAYS = 3
    const val NEW_NORMAL_DAYS = 28
    const val DEFAULT_VAR = .04
    const val DRIFT_PER_DAY = .0025
    const val ACUTE_ALPHA = .5
    const val RESPONSE_SCALE = .4
    const val DEBT_TAU_DAYS = 7.0
    const val DEBT_SCALE_H = 2.0
    const val MAX_SURPLUS_H = 2.0

    enum class Status { WARMUP, NORMAL, ABOVE, LOW, ALERT, PERSISTENT_CHANGE, NO_DATA }

    data class Day(
        val date: LocalDate,
        val recovery: Double?,
        val autonomicScore: Double?,
        val sleepPressureScore: Double?,
        val sleepDebtScore: Double?,
        val sleepDebtHours: Double?,
        val referenceMs: Double?,
        val referenceProvisional: Boolean,
        val referenceN: Int,
        val status: Status,
        val episodeDays: Int,
    )

    private class Hist(val o: Long, val v: Double, val excluded: Boolean)

    private class Ref(val value: Double, val swc: Double, val onset: Double, val n: Int, val kind: String)

    private class St {
        var hist = ArrayList<Hist>()
        var low = 0
        var good = 0
        var alert = false
        var episode = 0
        var acute: Double? = null
        var last: Long? = null
        var frozen: List<Double>? = null
        fun reset() {
            hist = ArrayList(); low = 0; good = 0; alert = false; episode = 0
            acute = null; last = null; frozen = null
        }
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Varianza campionaria come statistics.variance di Python. */
    private fun variance(xs: List<Double>): Double {
        val m = xs.average()
        return xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
    }

    private fun reference(st: St, today: Long): Ref? {
        val usable = st.hist.filter { !it.excluded && it.o < today }
        val full = usable.filter { it.o in (today - WINDOW_DAYS)..(today - EXCLUDE_DAYS - 1) }.map { it.v }
        var values: List<Double>
        var kind: String
        if (full.size >= FULL_REFERENCE_N) {
            values = full; kind = "full"
        } else {
            values = usable.map { it.v }; kind = "provisional"
        }
        val frozen = st.frozen
        if (frozen != null && (st.alert || full.size < FULL_REFERENCE_N)) {
            if (st.alert || values.size < frozen.size) {
                values = frozen; kind = "frozen"
            }
        }
        if (values.isEmpty()) return null
        val ref = median(values)
        val v = if (values.size >= 3) variance(values) else DEFAULT_VAR
        val sd = sqrt(v)
        return Ref(ref, max(SWC_FLOOR, SWC_FACTOR * sd), max(SWC_FLOOR, ONSET_FACTOR * sd), values.size, kind)
    }

    /**
     * Ricalcola tutta la storia (un archivio = un dispositivo) e restituisce il risultato per giorno.
     * [sleepNeedHours]: fabbisogno di sonno; [rebaselineDays]: giorni confermati come nuovo normale.
     */
    fun replay(
        history: List<DailySummary>,
        sleepNeedHours: Double,
        rebaselineDays: Set<LocalDate> = emptySet(),
    ): Map<LocalDate, Day> {
        val out = LinkedHashMap<LocalDate, Day>()
        val st = St()
        var stateS: Double? = null
        var debt: Double? = null
        var prevDate: LocalDate? = null
        var started = false

        for (d in history.sortedBy { it.date }) {
            val today = d.date.toEpochDay()
            // Giorni mancanti nel calendario: come in APSE, il processo S si interrompe.
            if (prevDate != null && d.date.toEpochDay() - prevDate.toEpochDay() > 1) stateS = null
            prevDate = d.date

            val rmssd = d.avgHrv?.takeIf { it in 1.0..500.0 }
            val v = rmssd?.let { ln(it) }
            val sleepH = if (d.hasSleep && d.asleepMin in 1..1440) d.asleepMin / 60.0 else null

            // --- sonno: processo S
            val sValue: Double? = if (sleepH == null) {
                stateS = null; null
            } else {
                val old = stateS ?: .25
                val wake = 1 - (1 - old) * exp(-(24 - sleepH) / 18.2)
                val p = wake * exp(-sleepH / 4.2)
                stateS = p; p
            }
            // --- debito di sonno
            if (sleepH != null) {
                val deficit = max(-MAX_SURPLUS_H, sleepNeedHours - sleepH)
                val cur = debt ?: 0.0
                debt = cur + (1 - exp(-1 / DEBT_TAU_DAYS)) * (deficit - cur)
            }
            val debtValue = debt?.let { max(0.0, it) }

            // --- riferimento HRV
            if (!started && v == null) {
                out[d.date] = Day(d.date, null, null, null, null, debtValue, null, false, 0, Status.NO_DATA, 0)
                continue
            }
            started = true
            if (d.date in rebaselineDays && v != null) st.reset()

            val ref = reference(st, today)
            var status = Status.WARMUP
            if (v != null) {
                st.acute = st.acute?.let { it + ACUTE_ALPHA * (v - it) } ?: v
                val recent = st.hist.filter { it.o >= today - 6 && it.o < today }.map { it.v } + v
                val weekMean = recent.average()
                if (ref != null && ref.n >= MIN_RECOVERY_N) {
                    val dd = v - ref.value
                    val low = dd < ABRUPT_DROP || (weekMean - ref.value < -ref.onset && dd < 0)
                    val normalToday = dd >= -ref.swc
                    st.low = if (low) st.low + 1 else 0
                    st.good = if (normalToday) st.good + 1 else 0
                    if (st.low >= ALERT_DAYS && !st.alert) {
                        st.alert = true
                        st.frozen = st.hist.filter {
                            !it.excluded && it.o < today &&
                                (ref.kind != "full" || it.o in (today - WINDOW_DAYS)..(today - EXCLUDE_DAYS - 1))
                        }.map { it.v }.ifEmpty { listOf(ref.value) }
                    }
                    if (st.alert && st.good >= RELEASE_DAYS) {
                        st.alert = false
                        st.episode = 0
                    }
                    status = if (low) Status.LOW else if (dd > ref.swc) Status.ABOVE else Status.NORMAL
                }
                val excluded = st.alert
                if (st.alert) st.episode += 1
                st.hist.add(Hist(today, v, excluded))
                st.hist = ArrayList(st.hist.filter { it.o > today - WINDOW_DAYS - 1 })
                st.last = today
            } else {
                status = Status.NO_DATA
            }
            if (st.alert) status = if (st.episode >= NEW_NORMAL_DAYS) Status.PERSISTENT_CHANGE else Status.ALERT

            // --- recupero
            var recovery: Double? = null
            var auton: Double? = null
            var sScore: Double? = null
            var dScore: Double? = null
            if (ref != null && ref.n >= MIN_RECOVERY_N && v != null && sValue != null && debtValue != null) {
                val delta = min(v, st.acute!!) - ref.value
                auton = 100 * exp(min(0.0, delta) / RESPONSE_SCALE)
                sScore = 100 * (1 - sValue)
                dScore = 100 * exp(-debtValue / DEBT_SCALE_H)
                recovery = (auton + (sScore + dScore) / 2) / 2
            }
            out[d.date] = Day(
                d.date, recovery, auton, sScore, dScore, debtValue,
                ref?.let { exp(it.value) }, ref?.kind == "provisional", ref?.n ?: 0, status, st.episode,
            )
        }
        return out
    }

    // Cache: la stessa storia viene ricalcolata una volta sola per tutte le schermate.
    private var cacheKey: Any? = null
    private var cacheValue: Map<LocalDate, Day> = emptyMap()

    @Synchronized
    fun cached(history: List<DailySummary>, sleepNeedHours: Double, rebaselineDays: Set<LocalDate>): Map<LocalDate, Day> {
        val key = Triple(history.hashCode(), sleepNeedHours, rebaselineDays)
        if (key != cacheKey) {
            cacheValue = replay(history, sleepNeedHours, rebaselineDays)
            cacheKey = key
        }
        return cacheValue
    }
}
