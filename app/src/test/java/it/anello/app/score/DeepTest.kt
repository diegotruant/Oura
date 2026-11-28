package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.Sample
import it.anello.app.data.SleepStage
import it.anello.app.data.StageSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DeepTest {
    private val zone = ZoneOffset.UTC
    private val end = LocalDate.of(2026, 11, 27) // venerdì

    /** Notte che finisce nel giorno [date]: a letto alle [bedHour] (ore dopo le 20 del giorno prima), per [hours] ore. */
    private fun night(date: LocalDate, hrv: Double, rhr: Double = 50.0, bedHour: Double = 3.0, hours: Double = 8.0): DailySummary {
        val start = date.minusDays(1).atTime(20, 0).toEpochSecond(zone) * 1000 + (bedHour * 3_600_000).toLong()
        val stop = start + (hours * 3_600_000).toLong()
        return DailySummary(
            date = date, sleepStartMs = start, sleepEndMs = stop,
            inBedMin = (hours * 60).toInt(), asleepMin = (hours * 60).toInt(), avgHrv = hrv, lowestHr = rhr,
        )
    }

    private fun series(n: Int, f: (Int) -> DailySummary) = (0 until n).map { f(it) }

    @Test fun hrvStateStableThenStrain() {
        // 60 giorni con HRV che oscilla tra 38 e 42, battito 50.
        val base = series(60) { i -> night(end.minusDays(59L - i), if (i % 2 == 0) 38.0 else 42.0, 50.0 + i % 2) }
        val stable = Deep.hrvState(base, end)!!
        assertEquals(Deep.HrvKind.STABLE, stable.kind)
        assertEquals(40.0, stable.normMs, 0.5)

        // Ultima settimana: HRV giù e battito su.
        val strain = base.map { if (it.date > end.minusDays(7)) it.copy(avgHrv = 30.0, lowestHr = 56.0) else it }
        assertEquals(Deep.HrvKind.STRAIN, Deep.hrvState(strain, end)!!.kind)
        // HRV giù e battito giù.
        val sat = base.map { if (it.date > end.minusDays(7)) it.copy(avgHrv = 30.0, lowestHr = 45.0) else it }
        assertEquals(Deep.HrvKind.SATURATION, Deep.hrvState(sat, end)!!.kind)
        // HRV su.
        val up = base.map { if (it.date > end.minusDays(7)) it.copy(avgHrv = 50.0) else it }
        assertEquals(Deep.HrvKind.RECOVERED, Deep.hrvState(up, end)!!.kind)
        // Media uguale ma oscillazioni molto più ampie.
        val wild = base.map { if (it.date > end.minusDays(7)) it.copy(avgHrv = if (it.date.dayOfMonth % 2 == 0) 25.0 else 64.0) else it }
        val w = Deep.hrvState(wild, end)!!
        assertTrue(w.cvHigh)
        assertEquals(Deep.HrvKind.UNSETTLED, w.kind)
    }

    @Test fun hrvStateNeedsData() {
        assertNull(Deep.hrvState(series(10) { night(end.minusDays(9L - it), 40.0) }, end))
    }

    @Test fun perfectRegularity() {
        val h = series(28) { night(end.minusDays(27L - it), 40.0) }
        val r = Deep.regularity(h, end, zone)!!
        assertEquals(100.0, r.sri!!, 1e-9)
        assertEquals(0.0, r.midpointSd, 1e-9)
        // A letto alle 23, 8 ore: punto medio alle 3:00.
        assertEquals("3:00", Deep.clock(r.midpoint))
        assertEquals(0.0, r.socialJetlag!!, 1e-9)
    }

    @Test fun weekendShiftIsSocialJetlagAndLowersRegularity() {
        val h = series(28) {
            val d = end.minusDays(27L - it)
            val weekend = d.dayOfWeek.value >= 6
            night(d, 40.0, bedHour = if (weekend) 5.0 else 3.0)
        }
        val r = Deep.regularity(h, end, zone)!!
        assertEquals(120.0, r.socialJetlag!!, 1e-6)
        val sri = r.sri!!
        assertTrue(sri < 100.0)
        assertTrue(sri > 50.0)
        val alerts = Deep.alerts(h, end, zone)
        assertTrue(alerts.any { it.title == "Jet lag sociale" })
    }

    @Test fun nadirPositionAndLateNight() {
        fun withHr(d: DailySummary, nadirAt: Double): DailySummary {
            val n = 96 // 8 ore a 5 minuti
            val pos = (nadirAt * n).toInt()
            return d.copy(hrSeries = (0 until n).map { i -> Sample(d.sleepStartMs!! + i * 300_000L, 50.0 + kotlin.math.abs(i - pos) * 0.2) })
        }
        val h = series(20) { withHr(night(end.minusDays(19L - it), 40.0), 0.4) }
        assertEquals(0.4, Deep.nadirPosition(h.last())!!, 0.02)
        assertFalse(Deep.nightShape(h, h.last())!!.late)

        val late = withHr(night(end, 40.0), 0.85)
        val shape = Deep.nightShape(h.dropLast(1) + late, late)!!
        assertEquals(0.4, shape.typical!!, 0.02)
        assertTrue(shape.late)
        assertNull(Deep.nadirPosition(night(end, 40.0)))
    }

    @Test fun fragmentationCountsAwakeningsInsideSleep() {
        fun withStages(d: DailySummary, awakenings: Int): DailySummary {
            val s = d.sleepStartMs!!
            val e = d.sleepEndMs!!
            val spans = ArrayList<StageSpan>()
            spans += StageSpan(s, s + 600_000, SleepStage.AWAKE) // prima di addormentarsi: non conta
            var t = s + 600_000
            repeat(awakenings) {
                spans += StageSpan(t, t + 1_800_000, SleepStage.LIGHT); t += 1_800_000
                spans += StageSpan(t, t + 300_000, SleepStage.AWAKE); t += 300_000
            }
            spans += StageSpan(t, e - 600_000, SleepStage.DEEP)
            spans += StageSpan(e - 600_000, e, SleepStage.AWAKE) // risveglio finale: non conta
            return d.copy(hypnogram = spans)
        }
        val h = series(15) { withStages(night(end.minusDays(15L - it), 40.0), 2) }
        val usual = Deep.fragmentation(h, h.last())!!
        assertEquals(2, usual.awakenings)
        assertEquals(10, usual.wasoMin)
        assertEquals(0.25, usual.perHour, 1e-9)
        assertFalse(usual.high)

        val bad = withStages(night(end, 40.0), 8)
        val f = Deep.fragmentation(h + bad, bad)!!
        assertEquals(8, f.awakenings)
        assertEquals(0.25, f.typicalPerHour!!, 1e-9)
        assertTrue(f.high)
        assertNull(Deep.fragmentation(h, night(end, 40.0)))
    }

    @Test fun leversRecoverAKnownEffect() {
        // HRV = 40 · exp(0,05 · (ore − 7)): ogni ora in più vale +5,1%. Orario a letto costante.
        val h = series(80) { i ->
            val hours = 6.0 + (i % 5) * 0.5
            night(end.minusDays(79L - i), 40.0 * kotlin.math.exp(0.05 * (hours - 7.0)), hours = hours)
        }
        val l = Deep.levers(h, end, zone)!!
        val s = l.sleepHours!!
        assertEquals(5.13, s.pct, 1.0)
        assertTrue(s.reliable)
        assertTrue(s.low > 0)
        // L'orario non varia: nessuna stima.
        assertNull(l.bedtime)
        assertNull(Deep.levers(h.take(10), h[9].date, zone))
    }

    @Test fun noAlertsOnAQuietRegularMonth() {
        val h = series(60) { i -> night(end.minusDays(59L - i), if (i % 2 == 0) 39.0 else 41.0) }
        assertTrue(Deep.alerts(h, end, zone).isEmpty())
        assertNotNull(Deep.regularity(h, end, zone))
    }
}
