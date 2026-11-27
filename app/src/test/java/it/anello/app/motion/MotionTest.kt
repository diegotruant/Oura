package it.anello.app.motion

import it.anello.app.ble.OuraProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Stessi segnali e stessi valori attesi di research/apse/tests/test_motion.py (parità con Python). */
class MotionTest {
    private fun sig(freq: Double, amp: Double, seconds: Int, hz: Int = 50) =
        Array(seconds * hz) { doubleArrayOf(0.0, 0.0, 1 + amp * sin(2 * PI * freq * it / hz)) }

    @Test fun walk() {
        val r = Motion.count(sig(1.8, 0.3, 60), 50.0)
        assertEquals(106, r.candidates)
        assertEquals(106, r.regular)
        assertEquals(106, r.steps)
        assertEquals(setOf(Motion.WALK), r.labels.toSet())
    }

    @Test fun run() {
        val r = Motion.count(sig(2.8, 0.8, 60), 50.0)
        assertEquals(165, r.steps)
        assertEquals(setOf(Motion.RUN), r.labels.toSet())
    }

    @Test fun smallHandMotionGivesNoSteps() {
        val rng = Random(1)
        val r = Motion.count(Array(3000) { doubleArrayOf(0.0, 0.0, 1 + (rng.nextDouble() - 0.5) * 0.04) }, 50.0)
        assertEquals(0, r.steps)
    }

    @Test fun noLossAtWindowBoundaries() {
        val a = Motion.analyze(sig(1.8, 0.3, 60), 50.0)
        assertEquals(a.peaks.size, a.windows.sumOf { it.peaks })
        assertEquals(1.8 * 59, a.peaks.size.toDouble(), 1.0)
        // caratteristiche della terza finestra, come calcolate da Python
        assertArrayEquals(doubleArrayOf(1.0, 0.212132, 2.152862, 108.0, 0.015278), a.windows[2].features, 1e-5)
    }

    @Test fun mixedAndTail() {
        val mixed = sig(1.8, 0.3, 30) + Array(1500) { doubleArrayOf(0.0, 0.0, 1.0) } + sig(1.8, 0.3, 35)
        val r = Motion.count(mixed, 50.0)
        assertEquals(List(3) { Motion.WALK } + List(3) { Motion.NON_LOCOMOTOR } + List(3) { Motion.WALK }, r.labels)
        assertEquals(250, r.unresolvedSamples)
        assertEquals(115, r.candidates)
        assertEquals(115, r.steps) // la coda eredita l'ultima etichetta
    }

    @Test fun regularPeaksAndBoutEdges() {
        // due tratti regolari (0,5 s tra i picchi) e una coppia isolata: valore atteso da Python
        assertArrayEquals(intArrayOf(0, 25, 50, 75, 400, 425, 450),
            Motion.regularPeaks(intArrayOf(0, 25, 50, 75, 400, 425, 450, 1000, 1020), 50.0))
    }

    @Test fun irregularHandMotionIsRejected() {
        // colpi della mano a intervalli irregolari: 76 picchi, 14 "regolari" per caso, 0 passi (come Python)
        val z = DoubleArray(3000) { 1.0 }
        val gaps = doubleArrayOf(0.4, 1.1, 0.3, 0.9, 1.6, 0.35, 0.8, 0.5, 1.3, 0.45)
        var i = 60
        repeat(8) { for (g in gaps) { i += (g * 50).toInt(); if (i + 10 < 3000) for (k in 0 until 10) z[i + k] = 1 + 0.5 * sin(PI * k / 10) } }
        val r = Motion.count(Array(3000) { doubleArrayOf(0.0, 0.0, z[it]) }, 50.0)
        assertEquals(76, r.candidates)
        assertEquals(14, r.regular)
        assertEquals(0, r.steps)
    }

    @Test fun validationReport() {
        val rep = StepValidation.report(listOf(
            StepValidation.Row(60, 100, 104), StepValidation.Row(60, 110, 108), StepValidation.Row(60, 160, 165),
            StepValidation.Row(120, 0, 6), StepValidation.Row(60, null, 0),
        ))
        assertEquals(3, rep.nLocomotor); assertEquals(2, rep.nNonLocomotor)
        assertEquals(3.6666666666666665, rep.mae!!, 1e-9)
        assertEquals(2.3333333333333335, rep.bias!!, 1e-9)
        assertEquals(2.981060606060606, rep.mape!!, 1e-9)
        assertEquals(-5.087106905179025, rep.loaLow!!, 1e-9)
        assertEquals(9.753773571845691, rep.loaHigh!!, 1e-9)
        assertEquals(2.0, rep.falseStepsPerMin!!, 1e-9)
    }

    @Test fun ruleRejectsIrregularSlowOrStill() {
        assertEquals(Motion.NON_LOCOMOTOR, Motion.ruleLabel(doubleArrayOf(1.0, 0.3, 2.0, 100.0, 0.6)))
        assertEquals(Motion.NON_LOCOMOTOR, Motion.ruleLabel(doubleArrayOf(1.0, 0.3, 2.0, 40.0, 0.1)))
        assertEquals(Motion.NON_LOCOMOTOR, Motion.ruleLabel(doubleArrayOf(1.0, 0.01, 0.2, 100.0, 0.1)))
        assertEquals(Motion.WALK, Motion.ruleLabel(doubleArrayOf(1.0, 0.3, 2.0, 110.0, 0.1)))
        assertEquals(Motion.RUN, Motion.ruleLabel(doubleArrayOf(1.0, 0.6, 5.0, 170.0, 0.1)))
    }

    @Test fun rejectsBadInput() {
        try { Motion.analyze(sig(1.8, 0.3, 5), 50.0); fail() } catch (e: IllegalArgumentException) { /* ok */ }
        try { Motion.analyze(sig(1.8, 0.3, 60), 5.0); fail() } catch (e: IllegalArgumentException) { /* ok */ }
    }

    @Test fun countsPerGFromStillSegment() {
        // conteggi grezzi a 2048 per g: 10 s di cammino, 4 s fermo, 10 s di cammino
        val raw = (sig(1.8, 0.3, 10) + Array(200) { doubleArrayOf(0.0, 0.0, 1.0) } + sig(1.8, 0.3, 10))
            .map { doubleArrayOf(it[0] * 2048, it[1] * 2048, it[2] * 2048) }.toTypedArray()
        val (scale, rel) = Motion.countsPerG(raw, 50.0)
        assertEquals(2048.0, scale, 1.0)
        assertTrue(rel < 0.01)
    }

    @Test fun classifierFitPredictReject() {
        val rng = Random(2)
        val x = ArrayList<DoubleArray>(); val y = ArrayList<String>()
        for ((name, c) in listOf(Motion.WALK to (1.0 to 110.0), Motion.RUN to (3.0 to 170.0), Motion.NON_LOCOMOTOR to (0.2 to 10.0))) {
            repeat(12) { x += doubleArrayOf(c.first + (rng.nextDouble() - 0.5) * 0.1, c.second + (rng.nextDouble() - 0.5) * 6); y += name }
        }
        val m = Classifier.fit(x, y, Motion.CLASSES)
        assertEquals(Motion.WALK, m.predict(doubleArrayOf(1.0, 111.0)))
        assertEquals(Motion.RUN, m.predict(doubleArrayOf(3.0, 168.0)))
        assertEquals(Motion.UNKNOWN, m.predict(doubleArrayOf(50.0, 900.0)))
        val ev = Classifier.evaluate(m, x, y, y)
        assertEquals(1.0, ev.accuracy, 1e-9)
        assertEquals(1.0 / 3, ev.majorityAccuracy, 1e-9)
        try { Classifier.fit(x.take(24), y.take(24), Motion.CLASSES); fail() } catch (e: IllegalArgumentException) { /* manca una classe */ }
    }

    @Test fun trainedModelCountsLikeRuleOnCleanWalk() {
        val feats = ArrayList<DoubleArray>(); val labels = ArrayList<String>()
        for ((f, a, name) in listOf(Triple(1.8, 0.3, Motion.WALK), Triple(2.8, 0.8, Motion.RUN), Triple(0.0, 0.0, Motion.NON_LOCOMOTOR))) {
            for (w in Motion.analyze(sig(f, a, 60), 50.0).windows) { feats += w.features; labels += name }
        }
        val m = Classifier.fit(feats, labels, Motion.CLASSES)
        assertEquals(Motion.count(sig(1.8, 0.3, 40), 50.0).steps, Motion.count(sig(1.8, 0.3, 40), 50.0, m).steps)
    }

    // ---- protocollo dell'accelerometro (valori dai test di open_oura)

    @Test fun acmFrame() {
        val f = OuraProtocol.parseAcm(byteArrayOf(0x33, 0x0c, 0x32, 0x01, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0))!!
        assertEquals(50, f.hz)
        assertEquals(1, f.seq)
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6)), f.samples.map { it.toList() })
        val neg = OuraProtocol.parseAcm(byteArrayOf(0x33, 0x06, 0x32, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0x10, 0x00))!!
        assertEquals(listOf(-1, -32768, 16), neg.samples[0].toList())
        assertNull(OuraProtocol.parseAcm(byteArrayOf(0x11, 0x08, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test fun acmCommands() {
        assertArrayEquals(byteArrayOf(0x06, 0x07, 0x20, 0, 0, 0, 1, 0, 0), OuraProtocol.realtimeAcm(1))
        assertArrayEquals(byteArrayOf(0x06, 0x04, 0, 0, 0, 0), OuraProtocol.realtimeOff())
    }
}
