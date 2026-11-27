package it.anello.app.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Motore dei passi da accelerometro, ripreso da APSE Ring Lab (LocalEngine.steps,
 * IntegratedEngine.motionFeatures, WindowedMotion) e corretto in un punto: i picchi vengono
 * cercati su tutta la registrazione con un filtro continuo e poi assegnati alle finestre, così
 * i passi a cavallo tra due finestre non vanno persi.
 *
 * 1. Modulo dell'accelerazione in g.
 * 2. Filtro passa-banda 0,5–4 Hz (media mobile esponenziale lenta sottratta, poi passa-basso).
 * 3. Picchi sopra una soglia adattiva (0,6 × valore efficace della finestra, minimo 0,06 g),
 *    distanti almeno 0,25 s.
 * 4. Finestre di 10 s: ogni finestra viene classificata come cammino, corsa o movimento non
 *    locomotorio; solo i picchi delle finestre locomotorie contano come passi.
 *
 * Soglie illustrative, non validate: vanno verificate con conteggio manuale o video.
 */
object Motion {
    const val WINDOW_S = 10.0
    const val WALK = "WALK"
    const val RUN = "RUN"
    const val NON_LOCOMOTOR = "NON_LOCOMOTOR"
    const val UNKNOWN = "UNKNOWN"
    val CLASSES = setOf(WALK, RUN, NON_LOCOMOTOR)

    class Window(
        val start: Int, val end: Int,
        /** media del modulo (g), deviazione standard (g), jerk medio (g/s), cadenza (passi/min), irregolarità (CV degli intervalli). */
        val features: DoubleArray,
        val peaks: Int,
    )

    class Analysis(val hz: Double, val peaks: IntArray, val windows: List<Window>, val unresolvedSamples: Int)

    fun magnitude(xyz: Array<DoubleArray>): DoubleArray =
        DoubleArray(xyz.size) { sqrt(xyz[it][0] * xyz[it][0] + xyz[it][1] * xyz[it][1] + xyz[it][2] * xyz[it][2]) }

    /** Segnale filtrato passa-banda 0,5–4 Hz. */
    fun bandpass(mag: DoubleArray, hz: Double): DoubleArray {
        val slow = 1 - exp(-2 * Math.PI * 0.5 / hz)
        val fast = 1 - exp(-2 * Math.PI * 4 / hz)
        val out = DoubleArray(mag.size)
        var base = if (mag.isEmpty()) 0.0 else mag[0]
        var lp = 0.0
        for (i in mag.indices) {
            base += slow * (mag[i] - base)
            lp += fast * (mag[i] - base - lp)
            out[i] = lp
        }
        return out
    }

    /**
     * Analizza una registrazione: [xyz] in g, campionata uniformemente a [hz] (10–200 Hz).
     * Servono almeno 10 secondi.
     */
    fun analyze(xyz: Array<DoubleArray>, hz: Double): Analysis {
        require(hz.isFinite() && hz in 10.0..200.0) { "frequenza di campionamento non valida" }
        val width = Math.round(WINDOW_S * hz).toInt()
        require(xyz.size >= width) { "servono almeno 10 secondi" }
        val mag = magnitude(xyz)
        require(mag.all { it.isFinite() && it <= 32 }) { "accelerazione non valida" }
        val f = bandpass(mag, hz)

        // Soglia adattiva per finestra (l'ultima finestra incompleta usa i propri campioni).
        val nWin = xyz.size / width
        val thresholds = DoubleArray(nWin + 1)
        for (w in 0..nWin) {
            val a = w * width
            val b = minOf(xyz.size, a + width)
            if (a >= b) { thresholds[w] = Double.MAX_VALUE; continue }
            var sum = 0.0
            for (i in a until b) sum += f[i] * f[i]
            thresholds[w] = max(0.06, 0.6 * sqrt(sum / (b - a)))
        }

        val minGap = (0.25 * hz).toInt().coerceAtLeast(1)
        val settle = hz.toInt() // il primo secondo serve al filtro per assestarsi
        val peaks = ArrayList<Int>()
        var last = -1_000_000
        for (i in max(1, settle) until f.size - 1) {
            val thr = thresholds[i / width]
            if (f[i] > thr && f[i] > f[i - 1] && f[i] >= f[i + 1] && i - last >= minGap) {
                peaks += i
                last = i
            }
        }

        val windows = ArrayList<Window>()
        for (w in 0 until nWin) {
            val a = w * width
            val b = a + width
            var mean = 0.0; var sq = 0.0; var jerk = 0.0
            for (i in a until b) {
                mean += mag[i]; sq += mag[i] * mag[i]
                if (i > a) jerk += abs(mag[i] - mag[i - 1]) * hz
            }
            mean /= width
            val sd = sqrt(max(0.0, sq / width - mean * mean))
            val inWin = peaks.filter { it in a until b }
            val cadence = inWin.size * 60.0 / WINDOW_S
            val intervals = inWin.zipWithNext { p, q -> (q - p) / hz }
            val cv = if (intervals.size >= 2) {
                val m = intervals.average()
                sqrt(intervals.sumOf { (it - m) * (it - m) } / intervals.size) / m
            } else 1.0
            windows += Window(a, b, doubleArrayOf(mean, sd, jerk / (width - 1), cadence, cv), inWin.size)
        }
        return Analysis(hz, peaks.toIntArray(), windows, xyz.size % width)
    }

    /**
     * Regola semplice quando non c'è un classificatore addestrato: è locomozione se la cadenza
     * è plausibile (70–210 passi/min), i passi sono regolari e c'è movimento sufficiente.
     */
    fun ruleLabel(features: DoubleArray): String {
        val (_, sd, _, cadence, cv) = features
        return when {
            cadence !in 70.0..210.0 || cv > 0.35 || sd < 0.05 -> NON_LOCOMOTOR
            cadence >= 150 -> RUN
            else -> WALK
        }
    }

    class Result(
        /** Picchi candidati, prima della classificazione. */
        val candidates: Int,
        /** Passi accettati: picchi nelle finestre di cammino o corsa. */
        val steps: Int,
        val labels: List<String>,
        val unknownWindows: Int,
        val unresolvedSamples: Int,
        /** true se è stato usato il classificatore addestrato, false la regola semplice. */
        val trained: Boolean,
    )

    fun count(xyz: Array<DoubleArray>, hz: Double, model: Classifier.Model? = null): Result {
        val a = analyze(xyz, hz)
        val labels = a.windows.map { model?.predict(it.features) ?: ruleLabel(it.features) }
        var steps = 0
        a.windows.forEachIndexed { i, w -> if (labels[i] == WALK || labels[i] == RUN) steps += w.peaks }
        // La coda più corta di una finestra eredita l'etichetta dell'ultima finestra completa.
        val tailStart = a.windows.lastOrNull()?.end ?: 0
        if (labels.lastOrNull() == WALK || labels.lastOrNull() == RUN) steps += a.peaks.count { it >= tailStart }
        return Result(a.peaks.size, steps, labels, labels.count { it == UNKNOWN }, a.unresolvedSamples, model != null)
    }

    /**
     * Conteggi grezzi dell'anello → g. La scala dell'accelerometro non è documentata: si ricava
     * dal tratto più fermo della registrazione (2 s con la minore variazione), dove il modulo
     * vale 1 g. Ritorna (conteggi per g, variazione relativa del tratto): sotto il 2% circa la
     * stima è buona.
     */
    fun countsPerG(raw: Array<DoubleArray>, hz: Double): Pair<Double, Double> {
        val mag = magnitude(raw)
        val w = (2 * hz).toInt().coerceAtLeast(4)
        if (mag.size < w) {
            val m = mag.sorted()[mag.size / 2]
            return m to 1.0
        }
        var bestMean = 0.0
        var bestRel = Double.MAX_VALUE
        var i = 0
        while (i + w <= mag.size) {
            var s = 0.0; var q = 0.0
            for (k in i until i + w) { s += mag[k]; q += mag[k] * mag[k] }
            val m = s / w
            val rel = if (m > 0) sqrt(max(0.0, q / w - m * m)) / m else Double.MAX_VALUE
            if (rel < bestRel) { bestRel = rel; bestMean = m }
            i += w / 2
        }
        return bestMean to bestRel
    }
}

/**
 * Classificatore a centroidi con rifiuto dei casi incerti, ripreso da APSE Ring Lab
 * (CalibratedEngine). Le distanze non sono probabilità. Va addestrato e usato con le stesse
 * finestre e le stesse caratteristiche.
 */
object Classifier {
    class Model(
        val mean: DoubleArray, val scale: DoubleArray, val centers: Map<String, DoubleArray>,
        val maxDistance: Double, val minMargin: Double,
    ) {
        fun predict(x: DoubleArray): String {
            require(x.size == mean.size) { "numero di caratteristiche" }
            var best = Double.POSITIVE_INFINITY
            var second = Double.POSITIVE_INFINITY
            var label = Motion.UNKNOWN
            for ((name, c) in centers) {
                var d = 0.0
                for (j in x.indices) { val z = (x[j] - mean[j]) / scale[j] - c[j]; d += z * z }
                d = sqrt(d / x.size)
                if (d < best) { second = best; best = d; label = name } else second = minOf(second, d)
            }
            return if (best <= maxDistance && second - best >= minMargin) label else Motion.UNKNOWN
        }
    }

    /** Addestra il modello: servono almeno 3 esempi per ciascuna classe richiesta. */
    fun fit(
        x: List<DoubleArray>, labels: List<String>, required: Set<String>,
        maxDistance: Double = 3.0, minMargin: Double = 0.05,
    ): Model {
        require(x.size == labels.size && x.size >= 6) { "servono almeno 6 esempi" }
        val p = x[0].size
        require(x.all { it.size == p && it.all { v -> v.isFinite() } }) { "caratteristiche non valide" }
        val counts = labels.groupingBy { it }.eachCount()
        require(counts.keys == required && counts.values.all { it >= 3 }) { "servono almeno 3 esempi per ogni classe" }
        val mean = DoubleArray(p) { j -> x.sumOf { it[j] } / x.size }
        val scale = DoubleArray(p) { j -> max(1e-9, sqrt(x.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / x.size)) }
        val centers = required.sorted().associateWith { name ->
            val rows = x.indices.filter { labels[it] == name }
            DoubleArray(p) { j -> rows.sumOf { (x[it][j] - mean[j]) / scale[j] } / rows.size }
        }
        return Model(mean, scale, centers, maxDistance, minMargin)
    }

    class Evaluation(val accuracy: Double, val majorityAccuracy: Double, val n: Int, val rejected: Int)

    /** Accuratezza sul test e accuratezza del classificatore banale (sempre la classe più frequente). */
    fun evaluate(model: Model, x: List<DoubleArray>, labels: List<String>, trainLabels: List<String>): Evaluation {
        val majority = trainLabels.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val pred = x.map { model.predict(it) }
        val ok = pred.indices.count { pred[it] == labels[it] }
        return Evaluation(
            if (x.isEmpty()) 0.0 else ok.toDouble() / x.size,
            if (x.isEmpty()) 0.0 else labels.count { it == majority }.toDouble() / x.size,
            x.size, pred.count { it == Motion.UNKNOWN },
        )
    }
}
