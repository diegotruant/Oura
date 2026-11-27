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
