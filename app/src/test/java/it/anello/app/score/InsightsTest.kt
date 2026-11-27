package it.anello.app.score

import it.anello.app.data.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

class InsightsTest {
    private val d0 = LocalDate.of(2026, 1, 1)

    private fun night(i: Int, hrv: Double? = 50.0, rhr: Double? = 48.0, temp: Double? = 35.5, resp: Double? = 14.5) =
        DailySummary(d0.plusDays(i.toLong()), sleepStartMs = 1L, asleepMin = 450, avgHrv = hrv, restingHr = rhr,
            skinTemp = temp, respiratoryRate = resp)

    @Test fun t975MatchesTables() {
        assertEquals(2.228, Insights.t975(10.0), 0.01)
        assertEquals(2.042, Insights.t975(30.0), 0.005)
        assertEquals(1.96, Insights.t975(1e6), 0.001)
    }

    @Test fun temperatureDeviation() {
        val h = (0 until 20).map { night(it, temp = 35.5 + 0.05 * (it % 3 - 1)) } + night(20, temp = 36.1)
        val dev = Insights.temperature(h, h.last())!!
        assertEquals(0.6, dev.delta, 0.001)
        assertTrue(dev.z >= 2)
        assertNull(Insights.temperature(h.take(4), h[3]))          // storia troppo corta
        assertNull(Insights.temperature(h, night(21, temp = null))) // dato mancante
    }

    @Test fun illnessNeedsTwoSignals() {
        val base = (0 until 20).map { night(it, hrv = 50.0 + it % 3, rhr = 48.0 + it % 2, temp = 35.5 + 0.05 * (it % 3), resp = 14.5 + 0.1 * (it % 2)) }
        val oneSignal = night(20, temp = 36.3)
        assertFalse(Insights.illness(base + oneSignal, oneSignal)!!.alert)
        val sick = night(20, hrv = 32.0, rhr = 56.0, temp = 36.3, resp = 16.5)
        val ill = Insights.illness(base + sick, sick)!!
        assertTrue(ill.alert)
        assertEquals(4, ill.flagged)
        val normal = night(20)
        assertEquals(0, Insights.illness(base + normal, normal)!!.flagged)
    }

    @Test fun illnessCheckCountsTaggedDays() {
        val today = LocalDate.now()
        val h = (30 downTo 1).map { k ->
            DailySummary(today.minusDays(k.toLong()), sleepStartMs = 1L, asleepMin = 450, avgHrv = 50.0 + k % 3,
                restingHr = 48.0 + k % 2, skinTemp = 35.5 + 0.05 * (k % 3), respiratoryRate = 14.5)
        } + DailySummary(today, sleepStartMs = 1L, asleepMin = 450, avgHrv = 30.0, restingHr = 57.0, skinTemp = 36.4, respiratoryRate = 16.8)
        val c = Insights.illnessCheck(h, mapOf(today.minusDays(1) to setOf("malattia")))
        assertEquals(1, c.alertDays)
        assertEquals(2, c.sickDays)      // la notte del giorno segnato e quella successiva
        assertEquals(1, c.sickCaught)
    }

    @Test fun tagEffectDetected() {
        val rng = Random(3)
        val diary = HashMap<LocalDate, Set<String>>()
        val nights = ArrayList<DailySummary>()
        for (i in 0 until 60) {
            val alcohol = i % 4 == 0
            diary[d0.plusDays(i.toLong())] = if (alcohol) setOf("alcol") else emptySet()
            // la notte dopo (i+1) risente dell'alcol del giorno i: -20%
            val hrv = exp(ln(50.0) + (if (alcohol) ln(0.8) else 0.0) + (rng.nextDouble() - 0.5) * 0.1)
            nights += night(i + 1, hrv = hrv)
        }
        val e = Insights.tagEffects(nights, diary).single { it.tag == "alcol" }
        assertEquals(15, e.nWith)
        assertEquals(45, e.nWithout)
        assertEquals(-20.0, e.pct, 3.0)
        assertTrue(e.reliable)
        assertTrue(e.pctLow < e.pct && e.pct < e.pctHigh)
    }

    @Test fun tagEffectNeedsEnoughNights() {
        val diary = mapOf(d0 to setOf("stress"), d0.plusDays(1) to emptySet<String>())
        val e = Insights.tagEffects(listOf(night(1), night(2)), diary).single()
        assertTrue(e.pct.isNaN())
        assertFalse(e.reliable)
    }

    @Test fun noEffectIsNotReliable() {
        val rng = Random(5)
        val diary = (0 until 60).associate { d0.plusDays(it.toLong()) to if (it % 3 == 0) setOf("viaggio") else emptySet() }
        val nights = (1..60).map { night(it, hrv = exp(ln(50.0) + (rng.nextDouble() - 0.5) * 0.3)) }
        assertFalse(Insights.tagEffects(nights, diary).single().reliable)
    }

    @Test fun loadResponseAndForecast() {
        val rng = Random(11)
        val trueSlope = -0.0008 // 100 punti di carico = circa -8% di HRV il mattino dopo
        val loads = HashMap<LocalDate, Double>()
        val nights = ArrayList<DailySummary>()
        for (i in 0 until 20) nights += night(i, hrv = exp(ln(50.0) + (rng.nextDouble() - 0.5) * 0.06))
        for (i in 20 until 90) {
            val load = if (i % 3 == 0) 0.0 else 40.0 + rng.nextDouble() * 110
            if (load > 0) loads[d0.plusDays(i.toLong())] = load
            nights += night(i + 1, hrv = exp(ln(50.0) + trueSlope * load + (rng.nextDouble() - 0.5) * 0.06))
        }
        val apse = Apse.replay(nights, 8.0)
        val m = Insights.loadResponse(apse, nights, loads)!!
        assertTrue(m.ready)
        assertTrue("pendenza ${m.slope}", m.slope < -0.0003 && m.slope > -0.0013)
        assertTrue(m.slopeReliable)

        val rest = Insights.forecast(m, 0.0, 80.0)!!
        val hard = Insights.forecast(m, 200.0, 80.0)!!
        assertTrue(hard.recovery < rest.recovery)
        assertTrue(hard.low <= hard.recovery && hard.recovery <= hard.high)
        assertTrue(hard.hrvPct < rest.hrvPct)
    }

    @Test fun loadResponseNotReadyWithFewData() {
        val nights = (0 until 10).map { night(it) }
        val apse = Apse.replay(nights, 8.0)
        assertNull(Insights.loadResponse(apse, nights, emptyMap()))
        val m = Insights.loadResponse(apse, nights, mapOf(d0.plusDays(8) to 100.0))
        assertNotNull(m)
        assertFalse(m!!.ready)
        assertNull(Insights.forecast(m, 100.0, 80.0))
    }

    @Test fun recoveryMarginShrinksWithMoreNights() {
        val rng = Random(2)
        val nights = (0 until 60).map { night(it, hrv = exp(ln(50.0) + (rng.nextDouble() - 0.5) * 0.3)) }
        val out = Apse.replay(nights, 8.0)
        // margine > 0 solo quando l'HRV è sotto o vicino alla norma; in media si restringe
        val early = (6..15).mapNotNull { out[d0.plusDays(it.toLong())]?.recoveryMargin }.average()
        val late = (45..59).mapNotNull { out[d0.plusDays(it.toLong())]?.recoveryMargin }.average()
        assertTrue("early $early late $late", late < early)
    }
}
