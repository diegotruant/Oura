package it.anello.app.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset

class FitTrainingTest {

    private val t0 = 1_100_000_000L

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xff).toByte() }

    /** FIT minimale con record (orario, potenza, battito); [compressed] usa i timestamp compressi. */
    private fun encode(powers: List<Int?>, start: Long = t0, compressed: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x41, 0, 0, 20, 0, 3, 253.toByte(), 4, 0x86.toByte(), 7, 2, 0x84.toByte(), 3, 1, 0x02))
        body.write(byteArrayOf(0x42, 0, 0, 20, 0, 2, 7, 2, 0x84.toByte(), 3, 1, 0x02))
        powers.forEachIndexed { i, p ->
            val ts = start + i
            if (compressed && i % 4 != 0) {
                body.write(0x80 or (2 shl 5) or (ts and 0x1F).toInt())
            } else {
                body.write(0x01); body.write(le(ts, 4))
            }
            body.write(le((p ?: 0xFFFF).toLong(), 2)); body.write(120)
        }
        val b = body.toByteArray()
        val header = byteArrayOf(14, 0x20) + le(2132, 2) + le(b.size.toLong(), 4) + ".FIT".toByteArray()
        val h = header + le(Fit.crc16(header).toLong(), 2)
        val blob = h + b
        return blob + le(Fit.crc16(blob).toLong(), 2)
    }

    @Test fun crcCheckValue() = assertEquals(0xBB3D, Fit.crc16("123456789".toByteArray()))

    @Test fun roundtrip() {
        val r = Fit.decode(encode(List(120) { 100 + it }))
        assertEquals(120, r.size)
        assertEquals(Fit.Record(t0 + 7, 107, 120), r[7])
    }

    @Test fun compressedTimestampsAcrossRollover() {
        val r = Fit.decode(encode(List(100) { 200 }, start = t0 + 25, compressed = true))
        assertEquals((0 until 100).map { t0 + 25 + it }, r.map { it.timestamp })
    }

    @Test fun invalidPowerIsNull() {
        assertNull(Fit.decode(encode(listOf(null)))[0].powerW)
    }

    @Test fun corruptionDetected() {
        val b = encode(List(30) { 200 })
        b[40] = (b[40].toInt() xor 0xFF).toByte()
        try { Fit.decode(b); fail("atteso errore") } catch (e: Fit.FitException) { /* ok */ }
        try { Fit.decode(encode(List(30) { 200 }).copyOf(60)); fail("atteso errore") } catch (e: Fit.FitException) { /* ok */ }
        try { Fit.decode(ByteArray(40) { 'x'.code.toByte() }); fail("atteso errore") } catch (e: Fit.FitException) { /* ok */ }
    }

    @Test fun powerSeriesFillsGaps() {
        val a = Fit.decode(encode(List(5) { 200 }))
        val b = Fit.decode(encode(listOf(200), start = t0 + 10))
        val (series, filled) = Fit.powerSeries(a + b)
        assertEquals(11, series.size)
        assertEquals(5, filled)
        assertEquals(0.0, series[7], 0.0)
    }

    @Test fun normalizedPower() {
        assertEquals(200.0, Training.normalizedPower(DoubleArray(600) { 200.0 })!!, 1e-9)
        val bursty = DoubleArray(1200) { if ((it / 30) % 2 == 0) 400.0 else 0.0 }
        assertTrue(Training.normalizedPower(bursty)!! > bursty.average())
        assertNull(Training.normalizedPower(DoubleArray(10) { 200.0 }))
    }

    @Test fun wbal() {
        assertEquals(14_000.0, Training.wbalMin(DoubleArray(60) { 350.0 }, 250.0, 20_000.0), 1e-9)
        assertTrue(Training.wbalMin(DoubleArray(120) { 500.0 }, 250.0, 20_000.0) < 0)
    }

    @Test fun oneHourAtCpIsLoad100() {
        val a = Training.summarize(encode(List(3600) { 250 }), 250.0, 20_000.0, ZoneOffset.UTC)
        assertEquals(100.0, a.loadAu, 0.2)
        assertEquals(3600, a.seconds)
        assertEquals(64, a.id.length)
        assertEquals(LocalDate.of(2024, 11, 9), a.day)
        // stesso file = stesso id
        assertEquals(a.id, Training.summarize(encode(List(3600) { 250 }), 250.0, 20_000.0, ZoneOffset.UTC).id)
    }

    @Test fun noPowerRejected() {
        try { Training.summarize(encode(List(60) { null }), 250.0, 20_000.0); fail("atteso errore") }
        catch (e: Fit.FitException) { /* ok */ }
    }

    @Test fun formTracksLoad() {
        val d0 = LocalDate.of(2026, 1, 1)
        val loads = (0 until 30).associate { d0.plusDays(it.toLong()) to 100.0 }
        val f = Training.form(loads, d0.plusDays(40))
        assertEquals(41, f.size)
        assertTrue(f[29].acute > f[29].chronic)          // sotto carico: affaticato
        assertTrue(f[29].balance < 0)
        assertTrue(f.last().balance > 0)                  // dopo 10 giorni di riposo: fresco
        assertFalse(f.last().acute > f[29].acute)
    }
}
