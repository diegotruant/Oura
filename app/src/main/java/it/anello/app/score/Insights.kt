package it.anello.app.score

import it.anello.app.data.DailySummary
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analisi personali sui dati salvati. Tutto è calcolato sui dati di UNA persona e di UNA fonte:
 * sono associazioni osservate, non prove di causa. Soglie illustrative, non validate.
 */
object Insights {

    // ------------------------------------------------------------ norma personale

    data class Deviation(val value: Double, val norm: Double, val delta: Double, val z: Double, val n: Int)

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * Scostamento del valore di [day] dalla norma personale: mediana delle notti dei [days]
     * giorni precedenti. La dispersione è robusta (1,4826 × MAD) con un minimo [floor], così
     * una serie molto regolare non trasforma variazioni minime in scostamenti enormi.
     */
    fun deviation(
        history: List<DailySummary>, day: DailySummary, floor: Double,
        days: Long = 30, minN: Int = 5, f: (DailySummary) -> Double?,
    ): Deviation? {
        val v = f(day) ?: return null
        val prior = history.filter { it.date < day.date && it.date >= day.date.minusDays(days) }.mapNotNull(f)
        if (prior.size < minN) return null
        val m = median(prior)
        val mad = median(prior.map { abs(it - m) })
        val spread = max(floor, 1.4826 * mad)
        return Deviation(v, m, v - m, (v - m) / spread, prior.size)
    }

    fun temperature(history: List<DailySummary>, day: DailySummary): Deviation? =
        deviation(history, day, floor = 0.15) { it.skinTemp }

    // ------------------------------------------------------------ allarme multi-segnale

    data class Signal(val label: String, val text: String, val z: Double, val flagged: Boolean)
    data class Illness(val signals: List<Signal>, val flagged: Int, val alert: Boolean)

    const val SIGNAL_Z = 2.0

    /**
     * Quattro segnali rispetto alla norma personale: temperatura e battito a riposo più alti,
     * HRV più bassa, respiro più frequente. Un segnale è "fuori norma" oltre 2 dispersioni
     * nella direzione sfavorevole. Avviso con almeno 2 segnali fuori norma insieme: un
     * segnale solo capita spesso per caso. Non è una diagnosi.
     */
    fun illness(history: List<DailySummary>, day: DailySummary): Illness? {
        val out = ArrayList<Signal>()
        temperature(history, day)?.let {
            out += Signal("Temperatura", "%+.1f °C".format(it.delta), it.z, it.z >= SIGNAL_Z)
        }
        deviation(history, day, floor = 1.5) { it.restingHr }?.let {
            out += Signal("FC a riposo", "%+.0f bpm".format(it.delta), it.z, it.z >= SIGNAL_Z)
        }
        deviation(history, day, floor = 0.08) { d -> d.avgHrv?.takeIf { it > 0 }?.let { ln(it) } }?.let {
            out += Signal("HRV", "%+.0f%%".format((exp(it.delta) - 1) * 100), -it.z, -it.z >= SIGNAL_Z)
        }
        deviation(history, day, floor = 0.4) { it.respiratoryRate }?.let {
            out += Signal("Respiro", "%+.1f /min".format(it.delta), it.z, it.z >= SIGNAL_Z)
        }
        if (out.size < 2) return null
        val flagged = out.count { it.flagged }
        return Illness(out, flagged, flagged >= 2)
    }

    data class IllnessCheck(val alertDays: Int, val sickDays: Int, val sickCaught: Int, val evaluatedDays: Int)

    /** Verifica a posteriori: quanti giorni segnati "malattia" nel diario avevano l'avviso. */
    fun illnessCheck(history: List<DailySummary>, diary: Map<LocalDate, Set<String>>, days: Long = 90): IllnessCheck {
        val recent = history.filter { it.date >= LocalDate.now().minusDays(days) }
        var alerts = 0; var sick = 0; var caught = 0; var evaluated = 0
        for (d in recent) {
            val ill = illness(history, d) ?: continue
            evaluated++
            if (ill.alert) alerts++
            // La nota "malattia" del giorno D oppure del giorno prima vale per la notte che finisce in D.
            val tagged = "malattia" in (diary[d.date] ?: emptySet()) || "malattia" in (diary[d.date.minusDays(1)] ?: emptySet())
            if (tagged) { sick++; if (ill.alert) caught++ }
        }
        return IllnessCheck(alerts, sick, caught, evaluated)
    }

    // ------------------------------------------------------------ effetto delle etichette

    data class Effect(
        val tag: String, val nWith: Int, val nWithout: Int,
        /** Variazione percentuale dell'HRV nelle notti dopo un giorno con l'etichetta. */
        val pct: Double, val pctLow: Double, val pctHigh: Double,
        /** true se l'intervallo al 95% non comprende lo zero. */
        val reliable: Boolean,
    )

    const val MIN_GROUP = 4

    /** Quantile 0,975 della t di Student (approssimazione di Cornish-Fisher, buona per gl ≥ 3). */
    fun t975(df: Double): Double {
        val z = 1.959964
        return z + (z * z * z + z) / (4 * df) + (5 * z.pow5() + 16 * z * z * z + 3 * z) / (96 * df * df)
    }
    private fun Double.pow5() = this * this * this * this * this

    private fun mean(x: List<Double>) = x.sum() / x.size
    private fun variance(x: List<Double>): Double { val m = mean(x); return x.sumOf { (it - m) * (it - m) } / (x.size - 1) }

    /**
     * Per ogni etichetta confronta ln(HRV) delle notti DOPO i giorni con l'etichetta con le notti
     * dopo i giorni di diario compilato senza quell'etichetta (test di Welch, intervallo al 95%).
     * Servono almeno [MIN_GROUP] notti per gruppo. Attenzione: le etichette spesso vanno insieme
     * (es. alcol e cena tardiva), quindi l'effetto di una può contenere quello dell'altra.
     */
    fun tagEffects(history: List<DailySummary>, diary: Map<LocalDate, Set<String>>): List<Effect> {
        val byDate = history.associateBy { it.date }
        // (etichette del giorno D, ln HRV della notte che finisce in D+1)
        val pairs = diary.mapNotNull { (d, tags) ->
            byDate[d.plusDays(1)]?.avgHrv?.takeIf { it > 0 }?.let { tags to ln(it) }
        }
        val tags = pairs.flatMap { it.first }.toSet()
        return tags.mapNotNull { tag ->
            val with = pairs.filter { tag in it.first }.map { it.second }
            val without = pairs.filter { tag !in it.first }.map { it.second }
            if (with.size < MIN_GROUP || without.size < MIN_GROUP) {
                return@mapNotNull Effect(tag, with.size, without.size, Double.NaN, Double.NaN, Double.NaN, false)
            }
            val va = variance(with) / with.size
            val vb = variance(without) / without.size
            val se = sqrt(va + vb)
            val diff = mean(with) - mean(without)
            if (se <= 0) return@mapNotNull Effect(tag, with.size, without.size, (exp(diff) - 1) * 100, Double.NaN, Double.NaN, false)
            val df = (va + vb) * (va + vb) / (va * va / (with.size - 1) + vb * vb / (without.size - 1))
            val h = t975(max(3.0, df)) * se
            Effect(
                tag, with.size, without.size,
                (exp(diff) - 1) * 100, (exp(diff - h) - 1) * 100, (exp(diff + h) - 1) * 100,
                reliable = diff - h > 0 || diff + h < 0,
            )
        }.sortedWith(compareByDescending<Effect> { it.reliable }.thenBy { if (it.pct.isNaN()) 0.0 else it.pct })
    }

    data class Comparison(val nA: Int, val nB: Int, val pct: Double, val pctLow: Double, val pctHigh: Double, val reliable: Boolean)

    /**
     * Confronto di due gruppi di valori in logaritmo (test di Welch, intervallo al 95%):
     * variazione percentuale di A rispetto a B. Null se un gruppo ha meno di [MIN_GROUP] valori.
     */
    fun compareLn(a: List<Double>, b: List<Double>): Comparison? {
        if (a.size < MIN_GROUP || b.size < MIN_GROUP) return null
        val va = variance(a) / a.size
        val vb = variance(b) / b.size
        val se = sqrt(va + vb)
        val diff = mean(a) - mean(b)
        if (se <= 0) return Comparison(a.size, b.size, (exp(diff) - 1) * 100, Double.NaN, Double.NaN, false)
        val df = (va + vb) * (va + vb) / (va * va / (a.size - 1) + vb * vb / (b.size - 1))
        val h = t975(max(3.0, df)) * se
        return Comparison(a.size, b.size, (exp(diff) - 1) * 100, (exp(diff - h) - 1) * 100, (exp(diff + h) - 1) * 100,
            reliable = diff - h > 0 || diff + h < 0)
    }

    // ------------------------------------------------------------ risposta al carico e previsione

    data class LoadModel(
        val n: Int, val nTraining: Int,
        val intercept: Double, val slope: Double,
        val residualSd: Double, val meanLoad: Double, val sxx: Double,
        /** true se la pendenza è distinguibile da zero (intervallo al 95%). */
        val slopeReliable: Boolean,
    ) {
        val ready: Boolean get() = n >= MIN_PAIRS && nTraining >= MIN_TRAINING_DAYS && sxx > 0
    }

    const val MIN_PAIRS = 14
    const val MIN_TRAINING_DAYS = 5

    /**
     * Regressione lineare personale: scostamento dell'HRV dalla norma la mattina dopo
     * (ln HRV − ln norma) in funzione del carico di allenamento del giorno prima.
     * [loads]: carico per giorno; i giorni senza attività valgono 0 solo dal primo giorno con
     * un'attività importata in poi (prima non sappiamo se ti sei allenato).
     */
    fun loadResponse(apse: Map<LocalDate, Apse.Day>, history: List<DailySummary>, loads: Map<LocalDate, Double>): LoadModel? {
        val first = loads.keys.minOrNull() ?: return null
        val byDate = history.associateBy { it.date }
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for ((date, a) in apse) {
            val prev = date.minusDays(1)
            if (prev < first) continue
            val ref = a.referenceMs ?: continue
            if (a.referenceN < Apse.MIN_RECOVERY_N) continue
            val hrv = byDate[date]?.avgHrv?.takeIf { it > 0 } ?: continue
            xs += loads[prev] ?: 0.0
            ys += ln(hrv) - ln(ref)
        }
        val n = xs.size
        val nTraining = xs.count { it > 0 }
        if (n < MIN_PAIRS || nTraining < MIN_TRAINING_DAYS) return LoadModel(n, nTraining, 0.0, 0.0, 0.0, 0.0, 0.0, false)
        val mx = mean(xs); val my = mean(ys)
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0) return LoadModel(n, nTraining, 0.0, 0.0, 0.0, 0.0, 0.0, false)
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val intercept = my - slope * mx
        val sse = xs.indices.sumOf { val r = ys[it] - intercept - slope * xs[it]; r * r }
        val s = sqrt(sse / (n - 2))
        val slopeSe = s / sqrt(sxx)
        val reliable = abs(slope) > t975((n - 2).toDouble()) * slopeSe
        return LoadModel(n, nTraining, intercept, slope, s, mx, sxx, reliable)
    }

    data class Forecast(val recovery: Double, val low: Double, val high: Double, val hrvPct: Double)

    /**
     * Recupero previsto per domani dato il carico di oggi, con intervallo di previsione al 95%.
     * Ipotesi dichiarate: la parte "sonno" del punteggio resta quella di oggi ([sleepPart], 0-100)
     * e la risposta al carico è lineare. È una stima, non una promessa.
     */
    fun forecast(model: LoadModel, plannedLoad: Double, sleepPart: Double): Forecast? {
        if (!model.ready) return null
        val delta = model.intercept + model.slope * plannedLoad
        val h = t975((model.n - 2).toDouble()) * model.residualSd *
            sqrt(1 + 1.0 / model.n + (plannedLoad - model.meanLoad) * (plannedLoad - model.meanLoad) / model.sxx)
        fun rec(d: Double) = (100 * exp(min(0.0, d) / Apse.RESPONSE_SCALE) + sleepPart) / 2
        return Forecast(rec(delta), rec(delta - h), rec(delta + h), (exp(delta) - 1) * 100)
    }

    data class Advice(
        /** Carico massimo di oggi per restare sopra l'obiettivo domani; null se il carico non conta. */
        val maxLoad: Double?,
        /** false se l'obiettivo non è raggiungibile nemmeno a riposo (dipende dal sonno o dall'HRV di base). */
        val reachable: Boolean,
    )

    /**
     * Previsione al contrario: il carico più alto con cui il Recupero previsto (stima centrale)
     * resta almeno a [target]. Vale solo se sui tuoi dati il carico abbassa l'HRV.
     */
    fun maxLoadFor(model: LoadModel, target: Double, sleepPart: Double, cap: Double = 400.0): Advice? {
        if (!model.ready) return null
        val atRest = forecast(model, 0.0, sleepPart)!!.recovery
        if (atRest < target) return Advice(0.0, reachable = false)
        if (model.slope >= 0) return Advice(null, reachable = true)
        val autonomicNeeded = 2 * target - sleepPart
        if (autonomicNeeded <= 0) return Advice(cap, reachable = true)
        val deltaNeeded = Apse.RESPONSE_SCALE * ln(min(100.0, autonomicNeeded) / 100)
        val load = (deltaNeeded - model.intercept) / model.slope
        return Advice(load.coerceIn(0.0, cap), reachable = true)
    }
}
