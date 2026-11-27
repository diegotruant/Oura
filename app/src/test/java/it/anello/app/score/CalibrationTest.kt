package it.anello.app.score

import it.anello.app.data.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalibrationTest {
    private val d0 = LocalDate.of(2026, 1, 1)
    private fun night(i: Int, hrv: Double?, rhr: Double? = 45.0, sleep: Int = 400) =
        DailySummary(d0.plusDays(i.toLong()), sleepStartMs = if (sleep > 0) 1L else null, asleepMin = sleep,
            avgHrv = hrv, restingHr = rhr, skinTemp = 35.5)
    private fun map(vararg n: DailySummary) = n.associateBy { it.date }

    // Dispositivo A (es. Ring 4) e B (es. Gen3) che legge l'HRV il 25% più bassa e il battito 1 bpm più alto.
    private val a = (0 until 10).map { night(it, 44.0 + it) }.associateBy { it.date }
    private val b = (0 until 10).map { night(it, (44.0 + it) * 0.75, rhr = 46.0) }.associateBy { it.date }

    @Test fun learnsRatioAndOffset() {
        val c = Calibration.calibrate(a, b)
        assertEquals(10, c.n)
        assertTrue(c.ready)
        assertEquals(-25.0, c.bVsAPercent, 0.01)
        assertEquals(-1.0, c.rhrOffset!!, 1e-9)
    }

    @Test fun notReadyWithFewNights() {
        val c = Calibration.calibrate(a.filterKeys { it < d0.plusDays(3) }, b)
        assertEquals(3, c.n)
        assertFalse(c.ready)
        assertEquals(a, Calibration.fill(a, b, c, "B"))
    }

    @Test fun fillsMissingNightsConverted() {
        val c = Calibration.calibrate(a, b)
        val bMore = b + map(night(12, 30.0, rhr = 47.0), night(5, 99.0))
        val aWithGap = a + map(night(11, null, sleep = 0).copy(steps = 5000))
        val bGap = bMore + map(night(11, 36.0, rhr = 46.0))
        val out = Calibration.fill(aWithGap, bGap, c, "Gen3")
        // notte presente solo in B: convertita nella scala di A
        assertEquals(40.0, out.getValue(d0.plusDays(12)).avgHrv!!, 1e-9)
        assertEquals(46.0, out.getValue(d0.plusDays(12)).restingHr!!, 1e-9)
        assertEquals("Gen3", out.getValue(d0.plusDays(12)).filledFrom)
        assertNull(out.getValue(d0.plusDays(12)).skinTemp)
        // giorno di A senza sonno: notte di B, ma i passi restano quelli di A
        assertEquals(48.0, out.getValue(d0.plusDays(11)).avgHrv!!, 1e-9)
        assertEquals(5000L, out.getValue(d0.plusDays(11)).steps)
        // una notte che A ha già non viene toccata
        assertEquals(49.0, out.getValue(d0.plusDays(5)).avgHrv!!, 1e-9)
        assertNull(out.getValue(d0.plusDays(5)).filledFrom)
    }

    @Test fun agreementFlagsOutlierNight() {
        val c = Calibration.calibrate(a, b)
        val ok = Calibration.agreement(night(20, 50.0), night(20, 37.5), c)
        assertNotNull(ok); assertTrue(ok!!.agree)
        assertEquals(50.0, ok.hrvBConverted, 1e-9)
        val bad = Calibration.agreement(night(20, 50.0), night(20, 20.0), c)!!
        assertFalse(bad.agree)
        assertNull(Calibration.agreement(night(20, 50.0), night(20, null), c))
    }

    @Test fun filledSeriesKeepsBaselineContinuous() {
        // 30 notti con A, poi si passa a B (25% più basso): senza calibrazione sarebbe un "crollo".
        val aDays = (0 until 30).map { night(it, 50.0 + it % 3, sleep = 450) }.associateBy { it.date }
        val bDays = (20 until 60).map { night(it, (50.0 + it % 3) * 0.75, sleep = 450) }.associateBy { it.date }
        val c = Calibration.calibrate(aDays, bDays)
        assertTrue(c.ready)
        val merged = Calibration.fill(aDays, bDays, c, "B").values.sortedBy { it.date }
        val out = Apse.replay(merged, 8.0)
        assertTrue(out.values.none { it.status == Apse.Status.ALERT || it.status == Apse.Status.PERSISTENT_CHANGE })
        // senza calibrazione lo stesso passaggio fa scattare l'episodio
        val raw = (aDays + bDays.filterKeys { it !in aDays }).values.sortedBy { it.date }
        assertTrue(Apse.replay(raw, 8.0).values.any { it.status == Apse.Status.ALERT })
    }
}
