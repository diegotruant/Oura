package it.anello.app.dev

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class ColmiTest {
    @Test fun packetChecksum() {
        val p = Colmi.readSteps(0)
        assertEquals("43000f005f01", p.copyOfRange(0, 6).hex())
        assertEquals(0xb2, p[15].toInt() and 0xff)
        assertTrue(Colmi.valid(p))
        // Esempio reale dal client pubblico.
        assertTrue(Colmi.valid(hex("43f00501000000000000000000000039")))
        assertFalse(Colmi.valid(hex("43f00501000000000000000000000038")))
    }

    @Test fun setTimeIsBcdUtc() {
        val p = Colmi.setTime(LocalDateTime.of(2024, 1, 2, 3, 4, 5))
        assertEquals("0124010203040501", p.copyOfRange(0, 8).hex())
        val q = Colmi.setTime(LocalDateTime.of(2026, 11, 27, 23, 59, 30))
        assertEquals("0126112723593001", q.copyOfRange(0, 8).hex())
    }

    @Test fun battery() {
        assertEquals(Pair(64, false), Colmi.parseBattery(Colmi.packet(3, byteArrayOf(64, 0))))
        assertEquals(Pair(80, true), Colmi.parseBattery(Colmi.packet(3, byteArrayOf(80, 1))))
    }

    @Test fun stepsSingleSlot() {
        val parser = Colmi.StepsParser()
        assertNull(parser.feed(hex("43f00101000000000000000000000035")))
        val r = parser.feed(hex("432410155c0001790015001000000087"))
        assertNotNull(r)
        assertEquals(1, r!!.size)
        val s = r[0]
        assertEquals(LocalDate.of(2024, 10, 15), s.date)
        assertEquals(92, s.slot)
        assertEquals(1210, s.calories)
        assertEquals(21, s.steps)
        assertEquals(16, s.distanceM)
        // Intervallo 92 = 23:00 UTC.
        assertEquals(LocalDateTime.of(2024, 10, 15, 23, 0).toEpochSecond(ZoneOffset.UTC) * 1000, s.startMs)
    }

    @Test fun stepsMultiplePacketsAndNoData() {
        val parser = Colmi.StepsParser()
        val packets = listOf(
            "43f00501000000000000000000000039",
            "43230813100005c80030001b000000a9",
            "43230813140105b618aa046903000083",
            "432308131802053804e1009500000052",
            "432308131c030505026c004800000060",
            "432308134c0405ef016300440000006d",
        ).map { hex(it) }
        packets.forEach { assertTrue(Colmi.valid(it)) }
        var out: List<Colmi.StepSlot>? = null
        for (p in packets) out = parser.feed(p)
        assertEquals(5, out!!.size)
        assertEquals(listOf(48, 1194, 225, 108, 99), out.map { it.steps })
        assertEquals(LocalDate.of(2023, 8, 13), out[0].date)
        assertEquals(listOf(16, 20, 24, 28, 76), out.map { it.slot })

        val none = Colmi.StepsParser()
        assertEquals(emptyList<Colmi.StepSlot>(), none.feed(hex("43ff0000000000000000000000000042")))
        assertTrue(none.noData)
    }

    @Test fun heartRateLog() {
        val parser = Colmi.HrLogParser()
        assertFalse(parser.feed(hex("15001805000000000000000000000032")))
        assertFalse(parser.feed(hex("150180adb6660000000000000000005f")))
        for (sub in 2..23) {
            val data = ByteArray(14)
            data[0] = sub.toByte()
            if (sub == 15) data[3] = 89
            val done = parser.feed(Colmi.packet(21, data))
            assertEquals(sub == 23, done)
        }
        assertEquals(0x66b6ad80L, parser.timestamp)
        val samples = parser.samples(nowMs = Long.MAX_VALUE)
        assertEquals(1, samples.size)
        // Posizione 9 + 13·13 + 2 = 180 → 15:00 del giorno.
        assertEquals(0x66b6ad80L * 1000 + 180 * 300_000L, samples[0].tMs)
        assertEquals(89.0, samples[0].v, 0.0)

        val none = Colmi.HrLogParser()
        assertTrue(none.feed(hex("15ff0000000000000000000000000014")))
        assertTrue(none.noData)
    }

    @Test fun sleepFromBigData() {
        // 1 giorno; oggi; 10 byte; inizio 23:30 (1410), fine 07:00 (420); leggero 200, profondo 100, sveglio 10.
        val data = hex("01 00 0a 8205 a401 02c8 0364 050a")
        val big = Colmi.BigData()
        val frame = hex("bc27") + byteArrayOf(data.size.toByte(), 0, 0x12, 0x34) + data
        assertNull(big.feed(frame.copyOfRange(0, 9)))
        val full = big.feed(frame.copyOfRange(9, frame.size))
        assertEquals(39, full!!.first)
        assertArrayEquals(data, full.second)

        val now = LocalDateTime.of(2024, 10, 15, 12, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        val nights = Colmi.parseSleep(full.second, now)
        assertEquals(1, nights.size)
        val n = nights[0]
        assertEquals(LocalDateTime.of(2024, 10, 14, 23, 30).toEpochSecond(ZoneOffset.UTC) * 1000, n.startMs)
        assertEquals(LocalDateTime.of(2024, 10, 15, 7, 0).toEpochSecond(ZoneOffset.UTC) * 1000, n.endMs)
        assertEquals(listOf(Pair(2, 200), Pair(3, 100), Pair(5, 10)), n.periods)

        val slot = Colmi.StepSlot(LocalDate.of(2024, 10, 15), 40, 500, 20_000, 300)
        val hr = listOf(60.0, 50.0, 52.0, 58.0).mapIndexed { i, v -> it.anello.app.data.Sample(n.startMs + (i + 1) * 300_000L, v) }
        val days = Colmi.buildDays(listOf(slot), hr, nights, ZoneOffset.UTC)
        assertEquals(1, days.size)
        val d = days[0]
        assertEquals(LocalDate.of(2024, 10, 15), d.date)
        assertEquals(450, d.inBedMin)
        assertEquals(300, d.asleepMin)
        assertEquals(100, d.deepMin)
        assertEquals(200, d.lightMin)
        assertEquals(150, d.awakeMin)
        assertEquals(3, d.hypnogram.size)
        assertEquals(51.0, d.lowestHr!!, 1e-9)
        assertEquals(500L, d.steps)
        assertEquals(20.0, d.activeKcal!!, 1e-9)
    }
}

class RingConnTest {
    @Test fun sm3KnownAnswers() {
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", Sm3.hash("abc".toByteArray()).hex())
        assertEquals("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b", Sm3.hash(ByteArray(0)).hex())
        // Messaggio di 64 byte: obbliga a un secondo blocco.
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            Sm3.hash("abcd".repeat(16).toByteArray()).hex(),
        )
    }

    @Test fun authenticationVectors() {
        val ref = RingConn.macBytes("F8:79:99:F7:03:AD")
        assertEquals(0x59, RingConn.keyByte(ref))
        assertEquals("520be1", RingConn.authResponse(ref, 0xe5).hex())
        assertEquals("318267", RingConn.authResponse(ref, 0xb0).hex())
        // Catturato da un RingConn Gen 3 reale (dal progetto RingLink).
        val gen3 = RingConn.macBytes("00:00:00:21:F7:49")
        assertEquals(0x9f, RingConn.keyByte(gen3))
        assertEquals("85d72f", RingConn.authResponse(gen3, 0x89).hex())
        assertEquals("ec211a", RingConn.authResponse(gen3, 0x46).hex())
        assertArrayEquals(hex("01 01 85 d7 2f 00"), RingConn.authReply(hex("81 00 89 00"), "00:00:00:21:F7:49"))
    }

    @Test fun frameValidation() {
        assertTrue(RingConn.valid(hex("81 00 89 08")))
        assertFalse(RingConn.valid(hex("81 00 89 00")))
        assertTrue(RingConn.valid(hex("50 01 02")))
        assertFalse(RingConn.valid(ByteArray(0)))
    }

    @Test fun syncOpenCursor() {
        val cmd = RingConn.syncOpen(RingConn.CURSOR_EPOCH + 0x01020304L, RingConn.CHANNEL_ALL_DAY)
        assertEquals("020001020304030100", cmd.hex())
    }

    @Test fun descriptor() {
        val f = ByteArray(19)
        f[0] = 0x87.toByte(); f[1] = 80; f[2] = 4
        f[4] = 0x04; f[5] = 0xd2.toByte()
        f[6] = 0x01; f[7] = 0x55
        f[8] = 0x01; f[9] = 0x4a
        f[14] = 0x0f; f[15] = 0xa0.toByte()
        val d = RingConn.parseDescriptor(f)!!
        assertEquals(80, d.battery)
        assertTrue(d.charging)
        assertEquals(1234, d.steps)
        assertEquals(34.1, d.skinTempA, 1e-9)
        assertEquals(33.0, d.skinTempB, 1e-9)
        assertEquals(4000, d.batteryMv)
        assertNull(RingConn.parseDescriptor(ByteArray(19)))
    }

    private fun record(counter: Int, hr: Int, hrv: Int, resp8: Int, b8: Int): ByteArray {
        val r = ByteArray(23)
        r[2] = (counter shr 8).toByte(); r[3] = counter.toByte()
        r[4] = hr.toByte(); r[5] = hrv.toByte(); r[6] = 90; r[7] = resp8.toByte(); r[8] = b8.toByte(); r[9] = 0x0a
        for (i in 10..14) r[i] = 1
        return r
    }

    @Test fun historyRecords() {
        val page = ByteArray(3) + record(1000, 60, 45, 112, 97) + record(1150, 105, 30, 0, 0x12) + ByteArray(1)
        val recs = RingConn.parsePage(page)
        assertEquals(2, recs.size)
        val rest = recs[0]
        assertEquals(RingConn.RECORD_EPOCH + 1000, rest.unix)
        assertEquals(60, rest.hr)
        assertEquals(45, rest.hrv)
        assertEquals(14.0, rest.resp!!, 1e-9)
        assertEquals(97, rest.spo2)
        assertEquals(5, rest.motion)
        assertTrue(RingConn.toEpoch(rest).still)
        // Record di attività: il byte 8 è un'etichetta, non la SpO2; HRV e respiro non valgono.
        val act = recs[1]
        assertEquals(105, act.hr)
        assertNull(act.hrv)
        assertNull(act.spo2)
        assertNull(act.resp)
        // Battito sotto 30 = non misurato.
        assertNull(RingConn.parseRecord(record(1300, 12, 0, 0, 0), 0)!!.hr)
    }
}

class ProfilesTest {
    @Test fun standardUuids() {
        assertEquals(0x180d, Profiles.short(Profiles.uuid16(0x180d)))
        assertNull(Profiles.short(Colmi.UART_SERVICE))
        assertEquals("Battito cardiaco", Profiles.serviceName(Profiles.uuid16(0x180d)))
    }

    @Test fun families() {
        assertEquals(Profiles.Family.COLMI, Profiles.family(listOf(Profiles.uuid16(0x180a), Colmi.UART_SERVICE)))
        assertEquals(Profiles.Family.RINGCONN, Profiles.family(listOf(RingConn.SERVICE)))
        assertNull(Profiles.family(listOf(Profiles.uuid16(0x180d), Profiles.uuid16(0x180f))))
    }

    @Test fun measurements() {
        assertEquals(87, Profiles.battery(byteArrayOf(87)))
        assertEquals(36.5, Profiles.temperature(hex("00 6d0100ff"))!!, 1e-9)
        assertEquals(36.5, Profiles.temperature(hex("01 d10300ff"))!!, 1e-6) // 97,7 °F
        assertEquals(Pair(98.0, 60.0), Profiles.pulseOx(hex("00 6200 3c00")))
        assertEquals(250, Profiles.cyclingPower(hex("0000 fa00")))
        assertEquals(-5, Profiles.cyclingPower(hex("0000 fbff")))
        assertEquals("Polar H10", Profiles.text("Polar H10\u0000".toByteArray()))
    }
}

class SleepEstimatorTest {
    private val start = LocalDateTime.of(2024, 10, 14, 12, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    /** 24 ore a intervalli di 5 minuti: sveglio, un paio d'ore fermo sul divano, notte 23:00–07:00, mattina. */
    private fun day(): List<Epoch> = (0 until 288).map { i ->
        val hour = (12 + i / 12) % 24
        val t = start + i * 300_000L
        when {
            hour >= 23 || hour < 7 -> Epoch(t, 52, still = true, hrv = 60, resp = 14.0, spo2 = 97)
            hour in 15..16 -> Epoch(t, 75, still = true)
            hour in 7..11 -> Epoch(t, 78, still = false)
            else -> Epoch(t, 75, still = i % 2 == 0)
        }
    }

    @Test fun findsTheNightAndNotTheSofa() {
        val periods = SleepEstimator.detect(day(), 300_000L)
        assertEquals(1, periods.size)
        val p = periods[0]
        assertEquals(start + 11 * 3_600_000L, p.startMs)
        assertEquals(start + 19 * 3_600_000L, p.endMs)
        assertEquals(480 * 60_000L, p.asleepMs)
    }

    @Test fun shortNapsAndMissingDataAreIgnored() {
        assertTrue(SleepEstimator.detect(day().take(10), 300_000L).isEmpty())
        // Senza battito (anello non indossato) non c'è sonno.
        assertTrue(SleepEstimator.detect(day().map { it.copy(hr = null) }, 300_000L).isEmpty())
    }

    @Test fun buildsOneNightPerDay() {
        val nights = DeviceNights.build(day(), 300_000L, ZoneOffset.UTC)
        assertEquals(1, nights.size)
        val n = nights[0]
        assertEquals(LocalDate.of(2024, 10, 15), n.date)
        assertEquals(480, n.inBedMin)
        assertEquals(480, n.asleepMin)
        assertEquals(52.0, n.lowestHr!!, 1e-9)
        assertEquals(60.0, n.avgHrv!!, 1e-9)
        assertEquals(97.0, n.spo2!!, 1e-9)
        assertEquals(14.0, n.respiratoryRate!!, 1e-9)
        assertTrue(n.hasSleep)
    }
}
