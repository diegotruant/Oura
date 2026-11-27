package it.anello.app.score

import it.anello.app.ble.HrStrap
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

class HrvExperimentsTest {

    // ---- fascia cardio: pacchetto "Heart Rate Measurement"

    @Test fun parseHr8bitWithRr() {
        // flag 0x10 (RR presenti), 60 bpm, due RR da 1024 e 512 unità (1000 e 500 ms)
        val m = HrStrap.parse(byteArrayOf(0x10, 60, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(60, m.heartRate)
        assertEquals(listOf(1000.0, 500.0), m.rrMs)
    }

    @Test fun parseHr16bitEnergyAndRr() {
        // flag 0x19: battito a 16 bit, energia presente, RR presenti
        val m = HrStrap.parse(byteArrayOf(0x19, 0x2C, 0x01, 0x05, 0x00, 0x00, 0x04))!!
        assertEquals(300, m.heartRate)
        assertEquals(listOf(1000.0), m.rrMs)
    }

    @Test fun parseWithoutRr() {
        val m = HrStrap.parse(byteArrayOf(0x00, 72))!!
        assertEquals(72, m.heartRate)
        assertTrue(m.rrMs.isEmpty())
        assertNull(HrStrap.parse(byteArrayOf(0x00)))
    }

    // ---- RMSSD

    @Test fun rmssdOfAlternatingSeries() {
        val rr = List(120) { 900.0 + if (it % 2 == 0) 20 else -20 }
        val r = Hrv.rmssd(rr)
        assertEquals(40.0, r.rmssd!!, 1e-9)
        assertEquals(1.0, r.coverage, 1e-9)
        assertEquals(60_000.0 / 900, r.meanHr!!, 1e-6)
    }

    @Test fun rmssdRejectsArtifactsAndShortSeries() {
        assertNull(Hrv.rmssd(List(10) { 900.0 }).rmssd)                      // troppo corta
        val noisy = List(120) { if (it % 3 == 0) 1800.0 else 900.0 }          // un battito perso ogni tre
        assertNull(Hrv.rmssd(noisy).rmssd)
        assertTrue(Hrv.rmssd(noisy).coverage < 0.8)
        // un solo artefatto non rovina la misura e non entra nel calcolo
        val one = MutableList(120) { 900.0 + if (it % 2 == 0) 20 else -20 }.also { it[50] = 1900.0 }
        assertEquals(40.0, Hrv.rmssd(one).rmssd!!, 1e-9)
    }

    @Test fun pearson() {
        assertEquals(1.0, Hrv.pearson(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))!!, 1e-9)
        assertEquals(-1.0, Hrv.pearson(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0))!!, 1e-9)
        assertNull(Hrv.pearson(listOf(1.0, 2.0), listOf(1.0, 2.0)))
        assertNull(Hrv.pearson(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0)))
    }

    // ---- esperimenti

    private val d0 = LocalDate.of(2026, 3, 1)
    private fun night(date: LocalDate, hrv: Double) = DailySummary(date, sleepStartMs = 1L, asleepMin = 450, avgHrv = hrv)

    @Test fun phasesAlternate() {
        val e = Experiment("1", "x", d0, phaseDays = 7, cycles = 2)
        assertEquals(Experiments.Phase.NOT_STARTED, Experiments.phase(e, d0.minusDays(1)))
        assertEquals(Experiments.Phase.A, Experiments.phase(e, d0))
        assertEquals(Experiments.Phase.A, Experiments.phase(e, d0.plusDays(6)))
        assertEquals(Experiments.Phase.B, Experiments.phase(e, d0.plusDays(7)))
        assertEquals(Experiments.Phase.A, Experiments.phase(e, d0.plusDays(14)))
        assertEquals(Experiments.Phase.B, Experiments.phase(e, d0.plusDays(27)))
        assertEquals(Experiments.Phase.DONE, Experiments.phase(e, d0.plusDays(28)))
        assertEquals(1, Experiments.dayInPhase(e, d0.plusDays(7)))
        assertEquals(7, Experiments.dayInPhase(e, d0.plusDays(13)))
        assertEquals(d0.plusDays(27), Experiments.end(e))
    }

    @Test fun outcomeDetectsEffectAndSkipsTransitionDay() {
        val rng = Random(4)
        val e = Experiment("1", "niente caffè", d0, phaseDays = 7, cycles = 2)
        // la notte dopo un giorno di fase A ha HRV +15%
        val nights = (0 until 28).map { k ->
            val d = d0.plusDays(k.toLong())
            val boost = if (Experiments.phase(e, d) == Experiments.Phase.A) ln(1.15) else 0.0
            night(d.plusDays(1), exp(ln(50.0) + boost + (rng.nextDouble() - 0.5) * 0.06))
        }
        val out = Experiments.outcome(e, nights)
        assertEquals(12, out.nA)   // 2 fasi da 7 giorni, meno il primo giorno di ciascuna
        assertEquals(12, out.nB)
        val c = out.comparison!!
        assertEquals(15.0, c.pct, 3.0)
        assertTrue(c.reliable)
    }

    @Test fun outcomeNeedsEnoughNights() {
        val e = Experiment("1", "x", d0, phaseDays = 7)
        val out = Experiments.outcome(e, (1..4).map { night(d0.plusDays(it.toLong()), 50.0) })
        assertEquals(3, out.nA)
        assertNull(out.comparison)
    }

    // ---- consiglio di carico

    @Test fun maxLoadForTarget() {
        // modello: a riposo HRV pari alla norma, -10% ogni 100 punti di carico
        val m = Insights.LoadModel(n = 40, nTraining = 20, intercept = 0.0, slope = ln(0.9) / 100,
            residualSd = 0.05, meanLoad = 60.0, sxx = 100_000.0, slopeReliable = true)
        val a = Insights.maxLoadFor(m, target = 80.0, sleepPart = 90.0)!!
        assertTrue(a.reachable)
        assertNotNull(a.maxLoad)
        // al carico consigliato la previsione centrale vale l'obiettivo
        assertEquals(80.0, Insights.forecast(m, a.maxLoad!!, 90.0)!!.recovery, 1e-6)
        // obiettivo più alto = meno carico
        assertTrue(Insights.maxLoadFor(m, 90.0, 90.0)!!.maxLoad!! < a.maxLoad!!)
        // obiettivo non raggiungibile nemmeno a riposo
        assertFalse(Insights.maxLoadFor(m, 98.0, 90.0)!!.reachable)
        // se il carico non abbassa l'HRV non c'è limite da suggerire
        assertNull(Insights.maxLoadFor(m.copy(slope = 0.0001), 80.0, 90.0)!!.maxLoad)
        // modello non pronto
        assertNull(Insights.maxLoadFor(m.copy(n = 5), 80.0, 90.0))
    }
}
