package it.anello.app.score

import it.anello.app.ble.OuraEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StressTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decodesDaytimeBeats() {
        // Esempio reale da open_oura: primo battito (0x09 & 7) | (0x9d << 3) = 1257 ms, qualità 1.
        val beats = OuraEvents.decodeGreenIbi(hex("9d09940b9d0d9a099a09a62e946e"))
        assertEquals(7, beats.size)
        assertEquals(Pair(1257, 1), beats[0])
        // motion_event: 5 bit bassi del primo byte.
        assertEquals(0x6a and 0x1f, OuraEvents.decodeMotionSeconds(hex("6a512fc00702")))
    }

    @Test fun zones() {
        assertEquals(Stress.Zone.CALM, Stress.zone(54.0, 50.0, 0.2))
        assertEquals(Stress.Zone.ENGAGED, Stress.zone(62.0, 50.0, 0.2))
        assertEquals(Stress.Zone.TENSE, Stress.zone(75.0, 50.0, 0.2))
        // Battito alto ma in movimento: è attività, non tensione.
        assertEquals(Stress.Zone.ACTIVE, Stress.zone(75.0, 50.0, 0.6))
        assertEquals(Stress.Zone.ACTIVE, Stress.zone(60.0, 50.0, 0.9))
    }

    @Test fun windowsNeedEnoughBeatsAndSkipSleep() {
        val w = Stress.WINDOW_MS
        val t0 = 1_000 * w
        fun beats(window: Int, n: Int, ibi: Int) = (0 until n).map { Pair(t0 + window * w + it * 1000L, ibi) }
        val all = beats(0, 40, 1100) +   // 54,5 bpm, fermo → calma
            beats(1, 40, 800) +          // 75 bpm, fermo → tensione
            beats(2, 40, 800) +          // 75 bpm, in movimento → attività
            beats(3, 10, 800) +          // troppo pochi battiti
            beats(4, 40, 1100)           // durante il sonno
        val motion = (0 until 30).map { Pair(t0 + 2 * w + it * 30_000L, 28) }
        val sleep = listOf((t0 + 4 * w)..(t0 + 6 * w))
        val out = Stress.windows(all, motion, 50.0, sleep)
        assertEquals(listOf(Stress.Zone.CALM, Stress.Zone.TENSE, Stress.Zone.ACTIVE), out.map { it.zone })
        assertEquals(75.0, out[1].hr, 1e-9)
        val s = Stress.summary(out)
        assertEquals(15, s.calmMin)
        assertEquals(15, s.tenseMin)
        assertEquals(15, s.activeMin)
        assertEquals(45, s.measuredMin)
        assertTrue(Stress.windows(all, motion, 0.0).isEmpty())
    }
}
