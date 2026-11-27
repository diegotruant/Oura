package it.anello.app.motion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una sessione registrata con l'accelerometro dell'anello ed etichettata con l'attività svolta. */
data class MotionSession(
    val id: Long,
    val label: String,
    val seconds: Int,
    val hz: Int,
    val samples: Int,
    val lostFrames: Int,
    /** Conteggi grezzi per g usati per la conversione e qualità della stima (variazione relativa). */
    val countsPerG: Double,
    val scaleQuality: Double,
    val candidates: Int,
    /** Conteggio usato nell'app: classificatore addestrato se presente, altrimenti regola semplice. */
    val steps: Int,
    val trained: Boolean,
    /** Le tre configurazioni a confronto: deterministico, regola semplice, classificatore addestrato. */
    val regular: Int = steps,
    val ruleSteps: Int = steps,
    val modelSteps: Int? = null,
    /** Passi contati a mano o da video, inseriti dopo la sessione. */
    val trueSteps: Int? = null,
)

object MotionLabels {
    /** chiave → (testo, classe per l'addestramento; null = non usata). */
    val ALL: List<Triple<String, String, String?>> = listOf(
        Triple("walk", "Camminata", Motion.WALK),
        Triple("walk_pocket", "Camminata, mani in tasca", Motion.WALK),
        Triple("run", "Corsa", Motion.RUN),
        Triple("bike", "Bici", Motion.NON_LOCOMOTOR),
        Triple("typing", "Scrittura al PC", Motion.NON_LOCOMOTOR),
        Triple("teeth", "Lavare i denti", Motion.NON_LOCOMOTOR),
        Triple("driving", "Guida", Motion.NON_LOCOMOTOR),
        Triple("still", "Fermo", Motion.NON_LOCOMOTOR),
        Triple("other", "Altro", null),
    )
    fun text(key: String) = ALL.firstOrNull { it.first == key }?.second ?: key
    fun classOf(key: String) = ALL.firstOrNull { it.first == key }?.third
}

class MotionStore(context: Context) {
    private val dir = File(context.filesDir, "motion").apply { mkdirs() }
    private val index = File(dir, "sessions.json")
    private val modelFile = File(dir, "model.json")
    private val prefs = context.getSharedPreferences("motion", Context.MODE_PRIVATE)

    /** Ultima stima affidabile dei conteggi per g dell'accelerometro. */
    var countsPerG: Double
        get() = prefs.getFloat("counts_per_g", 0f).toDouble()
        set(v) { prefs.edit().putFloat("counts_per_g", v.toFloat()).apply() }

    @Synchronized
    fun sessions(): List<MotionSession> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(index.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MotionSession(o.getLong("id"), o.getString("label"), o.getInt("seconds"), o.getInt("hz"), o.getInt("samples"),
                    o.getInt("lostFrames"), o.getDouble("countsPerG"), o.getDouble("scaleQuality"), o.getInt("candidates"),
                    o.getInt("steps"), o.optBoolean("trained"),
                    o.optInt("regular", o.getInt("steps")), o.optInt("ruleSteps", o.getInt("steps")),
                    if (o.has("modelSteps")) o.getInt("modelSteps") else null,
                    if (o.has("trueSteps")) o.getInt("trueSteps") else null)
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveSessions(all: List<MotionSession>) {
        val arr = JSONArray()
        all.sortedBy { it.id }.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("label", s.label); put("seconds", s.seconds); put("hz", s.hz); put("samples", s.samples)
                put("lostFrames", s.lostFrames); put("countsPerG", s.countsPerG); put("scaleQuality", s.scaleQuality)
                put("candidates", s.candidates); put("steps", s.steps); put("trained", s.trained)
                put("regular", s.regular); put("ruleSteps", s.ruleSteps)
                s.modelSteps?.let { put("modelSteps", it) }
                s.trueSteps?.let { put("trueSteps", it) }
            })
        }
        val tmp = File(dir, "sessions.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    fun saveRaw(id: Long, samples: List<IntArray>) {
        File(dir, "$id.csv").bufferedWriter().use { w ->
            for (s in samples) { w.write("${s[0]},${s[1]},${s[2]}"); w.newLine() }
        }
    }

    fun loadRaw(id: Long): Array<DoubleArray> {
        val f = File(dir, "$id.csv")
        if (!f.exists()) return emptyArray()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 3) null else runCatching { doubleArrayOf(p[0].toDouble(), p[1].toDouble(), p[2].toDouble()) }.getOrNull()
        }.toTypedArray()
    }

    fun delete(id: Long) {
        File(dir, "$id.csv").delete()
        saveSessions(sessions().filterNot { it.id == id })
    }

    fun saveModel(m: Classifier.Model?) {
        if (m == null) { modelFile.delete(); return }
        val o = JSONObject()
        o.put("mean", JSONArray(m.mean.toList())); o.put("scale", JSONArray(m.scale.toList()))
        o.put("maxDistance", m.maxDistance); o.put("minMargin", m.minMargin)
        val c = JSONObject()
        m.centers.forEach { (k, v) -> c.put(k, JSONArray(v.toList())) }
        o.put("centers", c)
        modelFile.writeText(o.toString())
    }

    fun loadModel(): Classifier.Model? {
        if (!modelFile.exists()) return null
        return runCatching {
            val o = JSONObject(modelFile.readText())
            fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
            val c = o.getJSONObject("centers")
            Classifier.Model(arr(o.getJSONArray("mean")), arr(o.getJSONArray("scale")),
                c.keys().asSequence().associateWith { arr(c.getJSONArray(it)) }, o.getDouble("maxDistance"), o.getDouble("minMargin"))
        }.getOrNull()
    }

    /** Tutte le sessioni in un solo CSV, con accelerazione in g: per l'analisi fuori dall'app. */
    fun exportCsv(): String = buildString {
        appendLine("sessione,etichetta,classe,passi_veri,hz,t_s,x_g,y_g,z_g")
        for (s in sessions()) {
            val raw = loadRaw(s.id)
            val scale = if (s.countsPerG > 0) s.countsPerG else 1.0
            raw.forEachIndexed { i, r ->
                append(s.id).append(',').append(s.label).append(',').append(MotionLabels.classOf(s.label) ?: "").append(',')
                    .append(s.trueSteps?.toString() ?: "").append(',').append(s.hz).append(',')
                    .append("%.3f".format(java.util.Locale.US, i.toDouble() / s.hz)).append(',')
                    .append("%.4f,%.4f,%.4f".format(java.util.Locale.US, r[0] / scale, r[1] / scale, r[2] / scale)).append('\n')
            }
        }
    }

    /**
     * Registro per finestra (contratto di uscita): una riga per finestra di 10 s con candidati,
     * regolari, classe secondo la regola e secondo il modello, e versione dell'algoritmo.
     */
    fun exportWindowsCsv(): String = buildString {
        val model = loadModel()
        appendLine("sessione,etichetta,passi_veri,inizio_s,durata_s,hz,candidati,regolari,classe_regola,classe_modello,scala_incerta,versione")
        for (s in sessions()) {
            val raw = loadRaw(s.id)
            if (raw.size < 10 * s.hz || s.countsPerG <= 0) continue
            val g = Array(raw.size) { doubleArrayOf(raw[it][0] / s.countsPerG, raw[it][1] / s.countsPerG, raw[it][2] / s.countsPerG) }
            for (w in Motion.analyze(g, s.hz.toDouble()).windows) {
                append(s.id).append(',').append(s.label).append(',').append(s.trueSteps?.toString() ?: "").append(',')
                    .append(w.start / s.hz).append(',').append((w.end - w.start) / s.hz).append(',').append(s.hz).append(',')
                    .append(w.peaks).append(',').append(w.regular).append(',').append(Motion.ruleLabel(w.features)).append(',')
                    .append(model?.predict(w.features) ?: "").append(',').append(if (s.scaleQuality > 0.03) 1 else 0).append(',')
                    .append(Motion.VERSION).append('\n')
            }
        }
    }
}
