package it.anello.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.anello.app.ble.RingBle
import it.anello.app.ble.RingStore
import it.anello.app.motion.Classifier
import it.anello.app.motion.Motion
import it.anello.app.motion.MotionLabels
import it.anello.app.motion.MotionSession
import it.anello.app.motion.MotionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MotionUiState(
    val recording: Boolean = false,
    val samples: Int = 0,
    val status: String? = null,
    val sessions: List<MotionSession> = emptyList(),
    val hasModel: Boolean = false,
    val trainMessage: String? = null,
)

/** Sessioni di accelerometro etichettate, conteggio dei passi e addestramento del classificatore. */
class MotionViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MotionStore(app)
    private val ringStore = RingStore(app)
    private val ble = RingBle(app, ringStore)
    private var job: Job? = null

    private val _state = MutableStateFlow(MotionUiState())
    val state: StateFlow<MotionUiState> = _state

    init {
        viewModelScope.launch {
            val (s, m) = withContext(Dispatchers.IO) { store.sessions() to store.loadModel() }
            _state.update { it.copy(sessions = s, hasModel = m != null) }
        }
    }

    /** Converte i conteggi grezzi in g usando la scala stimata dal tratto più fermo. */
    private fun toG(raw: Array<DoubleArray>, hz: Double): Triple<Array<DoubleArray>, Double, Double> {
        val (scale, rel) = Motion.countsPerG(raw, hz)
        val use = when {
            rel < 0.03 && scale > 0 -> { store.countsPerG = scale; scale }
            store.countsPerG > 0 -> store.countsPerG
            else -> scale
        }
        return Triple(Array(raw.size) { doubleArrayOf(raw[it][0] / use, raw[it][1] / use, raw[it][2] / use) }, use, rel)
    }

    fun record(label: String, seconds: Int) {
        val address = ringStore.lastAddress
        if (address == null || ringStore.keyFor(address) == null) {
            _state.update { it.copy(status = "Associa prima un anello nella sezione sopra.") }
            return
        }
        if (_state.value.recording) return
        _state.update { it.copy(recording = true, samples = 0, status = "Collegamento all'anello…") }
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val cap = ble.recordMotion(address, seconds, log = {}) { n ->
                    _state.update { it.copy(samples = n, status = "Registrazione in corso: muoviti come indicato") }
                }
                if (cap.error != null && cap.samples.isEmpty()) return@withContext "Errore: ${cap.error}"
                val hz = if (cap.hz in 10..200) cap.hz else 50
                if (cap.samples.size < 10 * hz) return@withContext "Registrazione troppo corta (${cap.samples.size} campioni): servono almeno 10 secondi."
                val id = System.currentTimeMillis()
                store.saveRaw(id, cap.samples)
                val raw = Array(cap.samples.size) { i -> DoubleArray(3) { cap.samples[i][it].toDouble() } }
                val (g, scale, rel) = toG(raw, hz.toDouble())
                val model = store.loadModel()
                val rule = Motion.count(g, hz.toDouble())
                val trained = model?.let { Motion.count(g, hz.toDouble(), it) }
                val r = trained ?: rule
                store.saveSessions(store.sessions() + MotionSession(id, label, cap.samples.size / hz, hz, cap.samples.size,
                    cap.lostFrames, scale, rel, r.candidates, r.steps, r.trained,
                    regular = rule.regular, ruleSteps = rule.steps, modelSteps = trained?.steps))
                "Sessione salvata: ${r.steps} passi contati (${r.candidates} picchi candidati)" +
                    if (cap.lostFrames > 0) ", ${cap.lostFrames} pacchetti persi" else ""
            }
            val s = withContext(Dispatchers.IO) { store.sessions() }
            _state.update { it.copy(recording = false, status = result, sessions = s) }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(recording = false, status = "Registrazione annullata") }
    }

    fun setTrueSteps(id: Long, steps: Int?) = update { all -> all.map { if (it.id == id) it.copy(trueSteps = steps) else it } }

    fun delete(id: Long) = viewModelScope.launch {
        val s = withContext(Dispatchers.IO) { store.delete(id); store.sessions() }
        _state.update { it.copy(sessions = s) }
    }

    private fun update(f: (List<MotionSession>) -> List<MotionSession>) = viewModelScope.launch {
        val s = withContext(Dispatchers.IO) { store.saveSessions(f(store.sessions())); store.sessions() }
        _state.update { it.copy(sessions = s) }
    }

    /**
     * Addestra il classificatore sulle sessioni etichettate. Le sessioni sono divise tra
     * addestramento e test (una su tre al test), mai le finestre della stessa sessione, per non
     * gonfiare il risultato. Il modello viene salvato solo se sul test batte la scelta banale
     * della classe più frequente.
     */
    fun train() = viewModelScope.launch {
        val msg = withContext(Dispatchers.IO) {
            val sessions = store.sessions().filter { MotionLabels.classOf(it.label) != null }
            val trainX = ArrayList<DoubleArray>(); val trainY = ArrayList<String>()
            val testX = ArrayList<DoubleArray>(); val testY = ArrayList<String>()
            // Divisione per classe: la terza sessione di ogni classe (e ogni terza successiva) va al test.
            val seen = HashMap<String, Int>()
            for (s in sessions) {
                val cls = MotionLabels.classOf(s.label)!!
                val k = (seen[cls] ?: 0) + 1; seen[cls] = k
                val raw = store.loadRaw(s.id)
                if (raw.size < 10 * s.hz) continue
                val g = Array(raw.size) { doubleArrayOf(raw[it][0] / s.countsPerG, raw[it][1] / s.countsPerG, raw[it][2] / s.countsPerG) }
                val windows = Motion.analyze(g, s.hz.toDouble()).windows
                val toTest = k % 3 == 0
                for (w in windows) {
                    if (toTest) { testX += w.features; testY += cls } else { trainX += w.features; trainY += cls }
                }
            }
            val missing = Motion.CLASSES.filter { c -> trainY.count { it == c } < 3 }
            if (missing.isNotEmpty()) return@withContext "Servono più sessioni di: " + missing.joinToString { className(it) } +
                " (almeno 30 secondi ciascuna per l'addestramento)."
            if (testX.size < 3 || testY.toSet().size < 2) return@withContext "Servono almeno 3 sessioni per due classi diverse: " +
                "la terza di ogni classe viene tenuta da parte per la verifica."
            val model = Classifier.fit(trainX, trainY, Motion.CLASSES)
            val ev = Classifier.evaluate(model, testX, testY, trainY)
            if (ev.accuracy <= ev.majorityAccuracy) {
                return@withContext "Modello NON salvato: sul test azzecca il %.0f%% delle finestre, non meglio della scelta banale (%.0f%%). Servono più sessioni."
                    .format(ev.accuracy * 100, ev.majorityAccuracy * 100)
            }
            store.saveModel(model)
            // Riconta tutte le sessioni con il nuovo modello.
            store.saveSessions(store.sessions().map { s ->
                val raw = store.loadRaw(s.id)
                if (raw.size < 10 * s.hz) s else {
                    val g = Array(raw.size) { doubleArrayOf(raw[it][0] / s.countsPerG, raw[it][1] / s.countsPerG, raw[it][2] / s.countsPerG) }
                    val rule = Motion.count(g, s.hz.toDouble())
                    val r = Motion.count(g, s.hz.toDouble(), model)
                    s.copy(candidates = r.candidates, steps = r.steps, trained = true,
                        regular = rule.regular, ruleSteps = rule.steps, modelSteps = r.steps)
                }
            })
            "Modello salvato: sul test azzecca il %.0f%% di %d finestre (scelta banale %.0f%%, %d incerte). Verifica su sessioni di una sola persona: non vale per altri."
                .format(ev.accuracy * 100, ev.n, ev.majorityAccuracy * 100, ev.rejected)
        }
        val (s, m) = withContext(Dispatchers.IO) { store.sessions() to store.loadModel() }
        _state.update { it.copy(trainMessage = msg, sessions = s, hasModel = m != null) }
    }

    fun forgetModel() = viewModelScope.launch {
        withContext(Dispatchers.IO) { store.saveModel(null) }
        val s = withContext(Dispatchers.IO) {
            store.saveSessions(store.sessions().map { it.copy(steps = it.ruleSteps, trained = false, modelSteps = null) })
            store.sessions()
        }
        _state.update { it.copy(hasModel = false, sessions = s, trainMessage = "Modello eliminato: si usa la regola semplice.") }
    }

    fun exportCsv(): String = store.exportCsv()
    fun exportWindowsCsv(): String = store.exportWindowsCsv()

    private fun className(c: String) = when (c) {
        Motion.WALK -> "camminata"; Motion.RUN -> "corsa"; else -> "attività senza passi"
    }
}
