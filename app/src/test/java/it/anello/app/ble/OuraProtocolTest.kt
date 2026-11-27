package it.anello.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test sui byte reali: catture open_oura e il log del nostro anello Gen3 (fw 3.3.4). */
class OuraProtocolTest {

    private fun h(s: String) = RingStore.hexToBytes(s)

    @Test fun legacySummary_documentedExample() {
        // open_oura: "summary says 8 events and 3742 bytes left"
        val s = OuraProtocol.parseLegacySummary(h("110808009e0e00000300"))!!
        assertEquals(8, s.events)
        assertEquals(3742L, s.bytesLeft)
    }

    @Test fun legacySummary_drained() {
        val s = OuraProtocol.parseLegacySummary(h("110802000000000000ff"))!!
        assertEquals(0L, s.bytesLeft)
    }

    @Test fun firmware_ourRing() {
        val i = OuraProtocol.parseFirmware(h("091202000003030401000105000c04d010f838a0"))!!
        assertEquals("3.3.4", i.firmware)
        assertEquals("2.0.0", i.api)
        assertEquals("a0:38:f8:10:d0:04", i.mac)
    }

    @Test fun nonce_and_auth_states() {
        val n = OuraProtocol.parseNonce(h("2f102c322cd309e576508e6ed6a447559cf8"))
        assertNotNull(n); assertEquals(15, n!!.size)
        assertTrue(OuraProtocol.authOk(h("2f022e00")))
        assertFalse(OuraProtocol.authOk(h("2f022e02")))
        assertTrue(OuraProtocol.needsAuth(h("2f022f01")))
    }

    @Test fun encryptNonce_is16Bytes() {
        val enc = OuraProtocol.encryptNonce(ByteArray(16), ByteArray(15))
        assertEquals(16, enc.size)
    }

    @Test fun setAuthKey_frame() {
        val k = ByteArray(16) { it.toByte() }
        val f = OuraProtocol.setAuthKey(k)
        assertEquals(0x24, f[0].toInt()); assertEquals(0x10, f[1].toInt()); assertEquals(18, f.size)
        assertTrue(OuraProtocol.setKeyOk(h("250100")))
    }

    @Test fun splitFrames_concatenated() {
        val frames = OuraBle.splitFrames(h("2f022e00" + "0d0450000000"))
        assertEquals(2, frames.size)
        assertArrayEquals(h("2f022e00"), frames[0])
    }

    @Test fun event_tempFromOurRing() {
        // tag 0x46, ts 5867 (0x16eb), corpo con 7 temperature
        val e = OuraEvents.parse(h("4612" + "eb160000" + "160adb08cd074a0b1d0a94097509"))!!
        assertEquals(0x46, e.tag); assertEquals(5867L, e.ringDecis)
        val t = OuraEvents.decodeTemps(e.body)!!
        assertEquals(7, t.size); assertEquals(25.82, t[0], 0.001)
    }

    @Test fun timeSync_anchorsRingClock() {
        // Due time_sync reali: 186.6 s di anello = 186 s di orologio (stesso dato).
        val a = OuraEvents.decodeTimeSyncUnix(h("4f80086b0000000000"))!!
        val b = OuraEvents.decodeTimeSyncUnix(h("0981086b0000000000"))!!
        assertEquals(1795719247L, a)
        assertEquals(1795719433L, b)
        // L'orologio dell'anello concorda con quello del telefono entro 1 s.
        assertTrue(kotlin.math.abs(OuraEvents.unixAt(5886, 4020, a) - b) <= 1)
    }

    @Test fun sleepPhases_2bitCodes() {
        // header, poi 0b00_01_10_11 = deep, light, rem, awake
        val p = OuraEvents.decodeSleepPhases(byteArrayOf(0x01, 0x1b))!!
        assertArrayEquals(intArrayOf(0, 1, 2, 3), p)
    }

    @Test fun featureCommands() {
        // valori dalle catture di open_oura: stato 2f02200b, risposta 2f06210b00000000
        assertArrayEquals(h("2f02200b"), OuraProtocol.featureStatus(OuraProtocol.FEATURE_REAL_STEPS))
        assertArrayEquals(h("2f03220b01"), OuraProtocol.setFeatureMode(OuraProtocol.FEATURE_REAL_STEPS, 1))
        assertEquals(0, OuraProtocol.parseFeatureMode(h("2f06210b00000000"), 0x0b))
        assertEquals(1, OuraProtocol.parseFeatureMode(h("2f06210201000000"), 0x02))
        assertEquals(null, OuraProtocol.parseFeatureMode(h("2f06210201000000"), 0x0b))
        assertEquals(null, OuraProtocol.parseFeatureMode(h("2f022f01"), 0x0b))
    }

    @Test fun stepFeaturesUnpack() {
        val f = OuraEvents.decodeStepFeatures(ByteArray(14) { (it + 1).toByte() }.also { it[3] = 0x84.toByte(); it[11] = 0x8C.toByte() })!!
        assertEquals(14, f.size)
        assertEquals((1 shl 1) or 1, f[0])   // bit alto del byte 3 + byte 0
        assertEquals(4, f[3])                // 7 bit bassi del byte 3
        assertEquals((9 shl 1) or 1, f[8])
        assertEquals(12, f[11])
        assertEquals(null, OuraEvents.decodeStepFeatures(ByteArray(10)))
    }
}
