package it.anello.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RingNightsTest {

    private fun ev(tag: Int, ts: Long, body: ByteArray) = OuraEvents.Event(tag, OuraEvents.eventName(tag), ts, body)
    private fun u32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }

    @Test fun buildsNightFromBedtimeAndPages() {
        val unix = 1_795_700_000L // 2026-11-26 ~13:33 UTC
        val bs = 2_000L
        val be = bs + 36_000L // 1 ora = 120 epoche da 30 s
        val events = mutableListOf(
            ev(0x42, 1_000, u32(unix) + ByteArray(5)),
            ev(0x76, be + 5, u32(bs) + u32(be)),
            ev(0x5a, be + 10, byteArrayOf(0) + ByteArray(13) { 0x00 }),          // 52 profondo
            ev(0x5a, be + 11, byteArrayOf(1) + ByteArray(13) { 0xAA.toByte() }), // 52 REM
            ev(0x5a, be + 12, byteArrayOf(2) + ByteArray(13) { 0x55 }),          // 16 leggero usate
        )
        // Battito 50 bpm (100 * 0.5) ogni 30 s, respiro 14 (112 / 8)
        for (k in 0 until 100) {
            events += ev(0x6a, bs + 300L * k, byteArrayOf(100, 0, 0, 0, 112, 0, 5, 1, 0, 0))
        }
        events += ev(0x5d, bs + 1000, byteArrayOf(50, 40, 48, 30))

        val nights = RingNights.build(events, ZoneOffset.UTC)
        assertEquals(1, nights.size)
        val n = nights[0]
        assertEquals(60, n.inBedMin)
        assertEquals(26, n.deepMin)
        assertEquals(26, n.remMin)
        assertEquals(8, n.lightMin)
        assertEquals(60, n.asleepMin)
        assertEquals(50.0, n.avgSleepHr!!, 0.01)
        assertEquals(14.0, n.respiratoryRate!!, 0.01)
        assertEquals(35.0, n.avgHrv!!, 0.01)
        assertNotNull(n.lowestHr)
        // fine sonno = unix + (be - 1000)/10 s
        assertEquals((unix + (be - 1_000) / 10) * 1000, n.sleepEndMs)
        assertEquals(LocalDate.of(2026, 11, 26), n.date)
        assertEquals(3, n.hypnogram.size) // profondo, REM, leggero
    }
}
