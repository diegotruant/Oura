package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.SleepStage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Approfondimenti: misure prese dalla letteratura scientifica che di solito le app degli anelli
 * non mostrano. Ognuna è calcolata solo sui dati della persona e dichiara quando i dati non bastano.
 *
 * - Stato dell'HRV sulla settimana: media a 7 giorni di ln(RMSSD) contro la norma, con la minima
 *   variazione rilevante (0,5 deviazioni standard), il coefficiente di variazione a 7 giorni e
 *   il battito a riposo (Plews e colleghi 2012–2013; Le Meur e colleghi 2013 per la lettura
 *   "HRV giù e battito giù" negli atleti di endurance).
 * - Regolarità: Sleep Regularity Index (Phillips e colleghi 2017), variabilità del punto medio
 *   del sonno e jet lag sociale (Wittmann e colleghi 2006).
 * - Forma della notte: momento del battito minimo.
 * - Frammentazione: risvegli per ora di sonno.
 * - Leve personali: di quanto cambia la tua HRV per ogni ora di sonno in più e per ogni ora di
 *   ritardo nell'andare a letto (regressione lineare con intervallo al 95%).
 */
object Deep {
    private fun mean(x: List<Double>) = x.sum() / x.size
    private fun sd(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }
    private fun percentile(x: List<Double>, p: Double): Double {
        val s = x.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    /** Minuti trascorsi dal mezzogiorno precedente (0..1440): così la notte non si spezza a mezzanotte. */
    private fun fromNoon(ms: Long, zone: ZoneId): Double {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        val m = z.hour * 60 + z.minute + z.second / 60.0
        return (m - 720 + 1440) % 1440
    }

    /** Da minuti dopo mezzogiorno a ora dell'orologio "hh:mm". */
    fun clock(fromNoonMin: Double): String {
        val m = ((fromNoonMin + 720).toInt() % 1440 + 1440) % 1440
        return "%d:%02d".format(m / 60, m % 60)
    }

    private fun nights(history: List<DailySummary>, from: LocalDate, to: LocalDate) =
        history.filter { it.hasSleep && it.date >= from && it.date <= to }.sortedBy { it.date }

    // ------------------------------------------------------------------ Stato dell'HRV

    enum class HrvKind { STABLE, RECOVERED, STRAIN, SATURATION, LOW, UNSETTLED }

    data class HrvState(
        val kind: HrvKind,
        /** Media della settimana e norma, in ms (medie geometriche). */
        val weekMs: Double,
        val normMs: Double,
        /** Minima variazione rilevante, in percento della norma. */
        val swcPct: Double,
        /** Coefficiente di variazione di ln(RMSSD) negli ultimi 7 giorni, in percento, e quello abituale. */
        val cv7: Double,
        val cvTypical: Double?,
        val cvHigh: Boolean,
        val rhrWeek: Double?,
        val rhrNorm: Double?,
        val nWeek: Int,
    )

    const val MIN_WEEK = 4
    const val MIN_BASE = 14

    private fun cvOf(ln: List<Double>): Double = sd(ln) / mean(ln) * 100

    /**
     * Stato sulla settimana che finisce in [date]. La norma sono le notti da 60 a 8 giorni prima.
     * Servono almeno [MIN_WEEK] notti nella settimana e [MIN_BASE] nella norma.
     */
    fun hrvState(history: List<DailySummary>, date: LocalDate): HrvState? {
        fun lnHrv(d: DailySummary) = d.avgHrv?.takeIf { it > 0 }?.let { ln(it) }
        val week = nights(history, date.minusDays(6), date)
        val base = nights(history, date.minusDays(60), date.minusDays(7))
        val w = week.mapNotNull { lnHrv(it) }
        val b = base.mapNotNull { lnHrv(it) }
        if (w.size < MIN_WEEK || b.size < MIN_BASE) return null
        val swc = 0.5 * sd(b)
        val delta = mean(w) - mean(b)
        val hrv = if (delta > swc) 1 else if (delta < -swc) -1 else 0

        val wr = week.mapNotNull { it.lowestHr }
        val br = base.mapNotNull { it.lowestHr }
        val rhr = if (wr.size >= MIN_WEEK && br.size >= MIN_BASE) {
            val s = 0.5 * sd(br)
            val d = mean(wr) - mean(br)
            if (d > s) 1 else if (d < -s) -1 else 0
        } else null

        // Variabilità abituale: CV delle finestre di 7 giorni dentro la norma.
        val rolling = base.mapNotNull { end ->
            val win = base.filter { it.date > end.date.minusDays(7) && it.date <= end.date }.mapNotNull { lnHrv(it) }
            if (win.size >= MIN_WEEK) cvOf(win) else null
        }
        val cv7 = cvOf(w)
        val cvHigh = rolling.size >= 10 && cv7 > percentile(rolling, 0.9)

        val kind = when {
            hrv < 0 && rhr == 1 -> HrvKind.STRAIN
            hrv < 0 && rhr == -1 -> HrvKind.SATURATION
            hrv < 0 -> HrvKind.LOW
            cvHigh -> HrvKind.UNSETTLED
            hrv > 0 -> HrvKind.RECOVERED
            else -> HrvKind.STABLE
        }
        return HrvState(
            kind, exp(mean(w)), exp(mean(b)), (exp(swc) - 1) * 100, cv7,
            if (rolling.size >= 10) Insights.median(rolling) else null, cvHigh,
            if (wr.size >= MIN_WEEK) mean(wr) else null, if (br.size >= MIN_BASE) mean(br) else null, w.size,
        )
    }

    // ------------------------------------------------------------------ Regolarità

    data class Regularity(
        /** Sleep Regularity Index, da −100 a 100 (100 = stessi orari ogni giorno); null se mancano coppie di giorni consecutivi. */
        val sri: Double?,
        /** Punto medio abituale del sonno (minuti dopo mezzogiorno) e sua deviazione standard in minuti. */
        val midpoint: Double,
        val midpointSd: Double,
        /** Differenza del punto medio tra giorni liberi (risveglio di sabato e domenica) e gli altri, in minuti; positivo = più tardi nei liberi. */
        val socialJetlag: Double?,
        val nights: Int,
    )

    const val MIN_REG_NIGHTS = 7

    private fun asleepAt(n: DailySummary, t: Long): Boolean {
        val s = n.sleepStartMs ?: return false
        val e = n.sleepEndMs ?: return false
        if (t < s || t >= e) return false
        return n.hypnogram.none { it.stage == SleepStage.AWAKE && t >= it.startMs && t < it.endMs }
    }

    fun regularity(history: List<DailySummary>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), days: Long = 28): Regularity? {
        val ns = nights(history, date.minusDays(days - 1), date)
        if (ns.size < MIN_REG_NIGHTS) return null
        val mids = ns.map { fromNoon((it.sleepStartMs!! + it.sleepEndMs!!) / 2, zone) }

        // SRI: probabilità che lo stato (sonno o veglia) sia lo stesso a 24 ore di distanza.
        val byDate = ns.associateBy { it.date }
        var same = 0
        var total = 0
        for (a in ns) {
            val b = byDate[a.date.plusDays(1)] ?: continue
            val t0 = a.date.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            for (k in 0 until 288) {
                val t = t0 + k * 300_000L
                if (asleepAt(a, t) == asleepAt(b, t + 86_400_000L)) same++
                total++
            }
        }
        val sri = if (total >= 5 * 288) -100.0 + 200.0 * same / total else null

        val free = ns.indices.filter { ns[it].date.dayOfWeek == DayOfWeek.SATURDAY || ns[it].date.dayOfWeek == DayOfWeek.SUNDAY }.map { mids[it] }
        val work = ns.indices.filter { ns[it].date.dayOfWeek != DayOfWeek.SATURDAY && ns[it].date.dayOfWeek != DayOfWeek.SUNDAY }.map { mids[it] }
        val jetlag = if (free.size >= 2 && work.size >= 4) mean(free) - mean(work) else null
        return Regularity(sri, Insights.median(mids), sd(mids), jetlag, ns.size)
    }

    // ------------------------------------------------------------------ Forma della notte

    data class NightShape(
        /** Posizione del battito minimo nella notte: 0 = inizio, 1 = fine. */
        val nadir: Double,
        val typical: Double?,
        val late: Boolean,
    )

    /** Posizione del battito minimo (media mobile su 3 campioni da 5 minuti); null se la serie è troppo corta. */
    fun nadirPosition(d: DailySummary): Double? {
        val s = d.sleepStartMs ?: return null
        val e = d.sleepEndMs ?: return null
        val hr = d.hrSeries
        if (hr.size < 12 || e <= s) return null
        var best = 1
        var bestV = Double.MAX_VALUE
        for (i in 1 until hr.size - 1) {
            val v = (hr[i - 1].v + hr[i].v + hr[i + 1].v) / 3
            if (v < bestV) { bestV = v; best = i }
        }
        return ((hr[best].tMs - s).toDouble() / (e - s)).coerceIn(0.0, 1.0)
    }

    /** Un minimo nell'ultimo terzo della notte, e più tardi del solito, indica che il corpo ha finito tardi di "smaltire" la giornata. */
    fun nightShape(history: List<DailySummary>, day: DailySummary): NightShape? {
        val pos = nadirPosition(day) ?: return null
        val prev = nights(history, day.date.minusDays(30), day.date.minusDays(1)).mapNotNull { nadirPosition(it) }
        val typical = if (prev.size >= 7) Insights.median(prev) else null
        return NightShape(pos, typical, pos >= 0.66 && (typical == null || pos - typical >= 0.15))
    }

    // ------------------------------------------------------------------ Frammentazione

    data class Fragmentation(
        val awakenings: Int,
        val perHour: Double,
        /** Minuti svegli dopo il primo addormentamento e prima dell'ultimo risveglio. */
        val wasoMin: Int,
        val typicalPerHour: Double?,
        val high: Boolean,
    )

    private fun fragments(d: DailySummary): Pair<Int, Int>? {
        val h = d.hypnogram
        if (h.isEmpty() || d.asleepMin < 60) return null
        val first = h.indexOfFirst { it.stage != SleepStage.AWAKE }
        val last = h.indexOfLast { it.stage != SleepStage.AWAKE }
        if (first < 0 || last <= first) return null
        val awake = h.subList(first, last).filter { it.stage == SleepStage.AWAKE && it.endMs - it.startMs >= 60_000 }
        return Pair(awake.size, (awake.sumOf { it.endMs - it.startMs } / 60_000).toInt())
    }

    /** Risvegli di almeno un minuto per ora di sonno, confrontati con la mediana delle 30 notti precedenti. */
    fun fragmentation(history: List<DailySummary>, day: DailySummary): Fragmentation? {
        val (n, waso) = fragments(day) ?: return null
        val perHour = n / (day.asleepMin / 60.0)
        val prev = nights(history, day.date.minusDays(30), day.date.minusDays(1)).mapNotNull { d ->
            fragments(d)?.let { it.first / (d.asleepMin / 60.0) }
        }
        val typical = if (prev.size >= 7) Insights.median(prev) else null
        return Fragmentation(n, perHour, waso, typical, typical != null && perHour > 1.5 * typical && perHour - typical >= 0.5)
    }

    // ------------------------------------------------------------------ Leve personali

    data class Lever(
        /** Variazione percentuale dell'HRV per ogni ora in più, con intervallo al 95%. */
        val pct: Double,
        val low: Double,
        val high: Double,
        val n: Int,
    ) {
        val reliable: Boolean get() = low > 0 || high < 0
    }

    data class Levers(val sleepHours: Lever?, val bedtime: Lever?, val n: Int)

    const val MIN_LEVER_NIGHTS = 21

    private fun slope(x: List<Double>, y: List<Double>): Lever? {
        val n = x.size
        if (n < MIN_LEVER_NIGHTS || sd(x) < 0.25) return null
        val mx = mean(x)
        val my = mean(y)
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val b = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
        val a = my - b * mx
        val sse = x.indices.sumOf { val r = y[it] - a - b * x[it]; r * r }
        val se = sqrt(sse / (n - 2) / sxx)
        val t = Insights.t975((n - 2).toDouble())
        return Lever((exp(b) - 1) * 100, (exp(b - t * se) - 1) * 100, (exp(b + t * se) - 1) * 100, n)
    }

    /**
     * Associazione, sulle ultime [days] notti, tra l'HRV della notte (rispetto alla mediana delle 28
     * notti precedenti, per togliere le tendenze lente) e due cose che si possono cambiare: le ore
     * dormite e l'orario in cui si va a letto (rispetto al proprio orario abituale).
     */
    fun levers(history: List<DailySummary>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), days: Long = 120): Levers? {
        val ns = nights(history, date.minusDays(days - 1), date).filter { (it.avgHrv ?: 0.0) > 0 }
        if (ns.size < MIN_LEVER_NIGHTS) return null
        val hours = ArrayList<Double>()
        val bed = ArrayList<Double>()
        val y = ArrayList<Double>()
        for ((i, d) in ns.withIndex()) {
            val prev = ns.subList(maxOf(0, i - 28), i).map { ln(it.avgHrv!!) }
            if (prev.size < 7) continue
            y += ln(d.avgHrv!!) - Insights.median(prev)
            hours += d.asleepMin / 60.0
            bed += fromNoon(d.sleepStartMs!!, zone) / 60.0
        }
        if (y.size < MIN_LEVER_NIGHTS) return Levers(null, null, y.size)
        val usual = Insights.median(bed)
        return Levers(slope(hours, y), slope(bed.map { it - usual }, y), y.size)
    }

    // ------------------------------------------------------------------ Segnalazioni

    /** [warning] = da tenere d'occhio; altrimenti è un'informazione. */
    data class Alert(val title: String, val text: String, val warning: Boolean)

    const val JETLAG_MIN = 60.0
    const val SRI_LOW = 75.0

    /** Segnalazioni per il giorno [date], dalla più importante. Lista vuota se non c'è niente da dire. */
    fun alerts(history: List<DailySummary>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Alert> {
        val out = ArrayList<Alert>()
        hrvState(history, date)?.let { s ->
            val nums = "HRV della settimana %.0f ms contro una norma di %.0f".format(s.weekMs, s.normMs)
            when (s.kind) {
                HrvKind.STRAIN -> out += Alert(
                    "Carico non smaltito",
                    "$nums, con il battito a riposo sopra il solito. È il quadro tipico di stress o fatica accumulata: qualche giorno più leggero di solito lo riporta a posto.",
                    true,
                )
                HrvKind.SATURATION -> out += Alert(
                    "HRV e battito entrambi bassi",
                    "$nums, ma anche il battito a riposo è sotto il solito. Negli sport di endurance, dopo molto volume, questo quadro può indicare una fatica profonda più che un buon recupero: conta come ti senti e come rendi in allenamento.",
                    true,
                )
                HrvKind.LOW -> out += Alert("HRV della settimana sotto la norma", "$nums. Il battito a riposo è nella norma.", true)
                HrvKind.UNSETTLED -> out += Alert(
                    "HRV instabile",
                    "La media della settimana è nella norma, ma da un giorno all'altro oscilla più del solito (variazione %.1f%%). Spesso succede quando il corpo sta assorbendo un carico nuovo.".format(s.cv7),
                    false,
                )
                else -> {}
            }
        }
        val day = history.firstOrNull { it.date == date }
        if (day != null && day.hasSleep) {
            nightShape(history, day)?.takeIf { it.late }?.let {
                out += Alert(
                    "Battito minimo tardivo",
                    "Stanotte il battito ha toccato il minimo al %.0f%% della notte".format(it.nadir * 100) +
                        (it.typical?.let { t -> " (di solito al %.0f%%)".format(t * 100) } ?: "") +
                        ". Capita dopo una cena tardiva, alcol o un allenamento intenso la sera.",
                    false,
                )
            }
            fragmentation(history, day)?.takeIf { it.high }?.let {
                out += Alert(
                    "Sonno più frammentato del solito",
                    "%d risvegli (%.1f all'ora, di solito %.1f), %d minuti sveglio durante la notte.".format(it.awakenings, it.perHour, it.typicalPerHour ?: 0.0, it.wasoMin),
                    false,
                )
            }
        }
        regularity(history, date, zone)?.let { r ->
            if (r.socialJetlag != null && abs(r.socialJetlag) >= JETLAG_MIN) out += Alert(
                "Jet lag sociale",
                "Nei giorni liberi dormi con il punto medio spostato di %.0f minuti rispetto agli altri giorni: è come cambiare fuso ogni fine settimana.".format(abs(r.socialJetlag)),
                false,
            )
            if (r.sri != null && r.sri < SRI_LOW) out += Alert(
                "Orari del sonno irregolari",
                "Indice di regolarità %.0f su 100 nelle ultime settimane. Orari più costanti sono associati a una salute migliore, a parità di ore dormite.".format(r.sri),
                false,
            )
        }
        return out.sortedByDescending { it.warning }
    }
}
