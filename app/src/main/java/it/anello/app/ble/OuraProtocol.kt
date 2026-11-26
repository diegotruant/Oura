package it.anello.app.ble

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Protocollo BLE dell'anello Oura (Ring 3/4/5), ricavato dal progetto open_oura (MIT).
 * Tutto deriva dall'app ufficiale decompilata e da catture reali: vedi i documenti
 * in docs/ di open_oura. I numeri non sono inventati.
 *
 * Formato pacchetto:  tag(1) | len(1) | payload(len)
 * Gli interi multi-byte sono little-endian.
 * Le operazioni "estese" usano tag esterno 0x2f e il primo byte del payload come sotto-tag.
 */
object OuraProtocol {

    // GATT (Constants dell'app ufficiale)
    val SERVICE: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
    val WRITE_CHAR: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")
    val NOTIFY_CHAR: UUID = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MANUFACTURER_ID = 0x02b2

    // --- Costruzione comandi -------------------------------------------------

    private fun frame(tag: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = tag.toByte()
        out[1] = payload.size.toByte()
        payload.copyInto(out, 2)
        return out
    }

    /** DeviceInfo: firmware, bootloader, API, MAC. Non richiede autenticazione. */
    fun firmware() = byteArrayOf(0x08, 0x03, 0x00, 0x00, 0x00)

    /** Livello batteria (richiede app-auth se è installata una chiave). */
    fun battery() = frame(0x0c)

    /** Info prodotto: lo slot serial (0x18 03 08 00 10). */
    fun serial() = byteArrayOf(0x18, 0x03, 0x08, 0x00, 0x10)
    fun hardwareId() = byteArrayOf(0x18, 0x03, 0x18, 0x00, 0x10)

    /** Richiesta nonce per l'autenticazione: 2f012b → risposta 2f10 2c <15 byte nonce>. */
    fun getAuthNonce() = byteArrayOf(0x2f, 0x01, 0x2b)

    /** Imposta la chiave di 16 byte su un anello resettato: 2410 <key>. Risposta 250100. */
    fun setAuthKey(key: ByteArray): ByteArray {
        require(key.size == 16) { "la chiave deve essere di 16 byte" }
        return frame(0x24, key)
    }

    /** Autenticazione: 2f 11 2d <16 byte nonce cifrato>. Risposta ok 2f022e00. */
    fun authenticate(encryptedNonce: ByteArray): ByteArray {
        require(encryptedNonce.size == 16)
        val payload = ByteArray(17)
        payload[0] = 0x2d
        encryptedNonce.copyInto(payload, 1)
        return frame(0x2f, payload)
    }

    /** Registra lo stream eventi (stile app): 16 01 02. */
    fun registerStream() = byteArrayOf(0x16, 0x01, 0x02)

    /** Sottoscrizioni per categoria di evento (stile app). */
    val categorySubscriptions: List<ByteArray> = listOf(
        byteArrayOf(0x18, 0x03, 0x14, 0x00, 0x10),
        byteArrayOf(0x18, 0x03, 0x18, 0x00, 0x10),
        byteArrayOf(0x18, 0x03, 0x28, 0x00, 0x09),
        byteArrayOf(0x18, 0x03, 0x34, 0x00, 0x04),
        byteArrayOf(0x18, 0x03, 0x04, 0x00, 0x10),
        byteArrayOf(0x18, 0x03, 0x08, 0x00, 0x10),
    )

    /** Sweep parametri (letture di stato non distruttive). */
    val paramSweep: List<ByteArray> = listOf(
        byteArrayOf(0x2f, 0x02, 0x20, 0x02),
        byteArrayOf(0x2f, 0x02, 0x20, 0x04),
        byteArrayOf(0x2f, 0x02, 0x20, 0x0b),
        byteArrayOf(0x2f, 0x02, 0x20, 0x0d),
        byteArrayOf(0x2f, 0x02, 0x20, 0x03),
    )

    /** Capabilities pagina 0 (per scegliere sync esteso o legacy). */
    fun capabilities(page: Int) = byteArrayOf(0x2f, 0x02, 0x01, page.toByte())

    /** Sincronizza l'orologio dell'anello con l'ora UTC del telefono (forma legacy 0x12). */
    fun syncTimeLegacy(unixSeconds: Long): ByteArray {
        // 12 09 <u64 timestamp LE> 00
        val payload = ByteArray(9)
        var t = unixSeconds
        for (i in 0 until 8) { payload[i] = (t and 0xff).toByte(); t = t shr 8 }
        payload[8] = 0x00
        return frame(0x12, payload)
    }

    /** Rilascia gli eventi bufferizzati prima di drenare. */
    fun dataFlush() = byteArrayOf(0x28, 0x01, 0x00)

    /** Chiede all'anello di post-processare il sonno (check_sleep_analysis false). */
    fun checkSleepAnalysis() = byteArrayOf(0x28, 0x01, 0x00)

    /**
     * GetEvent legacy: 10 09 <start_deciseconds u32 LE> <max_events u32 LE... > flags.
     * open_oura usa: 10 09 <cursor u32 LE> 00 00 00 ff  → qui ricostruito con cursore.
     * Forma osservata: 10 09 0000000008ffffffff (cursor=0, poi 08 ff ff ff ff).
     */
    fun getEventsLegacy(cursorDeciseconds: Long, maxEvents: Int = 255): ByteArray {
        val payload = ByteArray(9)
        var c = cursorDeciseconds
        for (i in 0 until 4) { payload[i] = (c and 0xff).toByte(); c = c shr 8 }
        // byte 4: max events (255), byte 5..8: flags 0xffffffff
        payload[4] = maxEvents.toByte()
        payload[5] = 0xff.toByte(); payload[6] = 0xff.toByte()
        payload[7] = 0xff.toByte(); payload[8] = 0xff.toByte()
        return frame(0x10, payload)
    }

    /** GetEvent esteso (Ring 5): 2f 0c 41 <buffer> <start_ms u64 LE> <max u16 LE>. */
    fun getEventsExtended(startMs: Long, maxEvents: Int = 4096, buffer: Int = 0): ByteArray {
        val payload = ByteArray(12)
        payload[0] = 0x41
        payload[1] = buffer.toByte()
        var t = startMs
        for (i in 0 until 8) { payload[2 + i] = (t and 0xff).toByte(); t = t shr 8 }
        payload[10] = (maxEvents and 0xff).toByte()
        payload[11] = ((maxEvents shr 8) and 0xff).toByte()
        return frame(0x2f, payload)
    }

    /** Abilita misure diurne di HR e SpO2 dopo un pairing (altrimenti restano spente). */
    fun enableDaytimeHr() = byteArrayOf(0x2f, 0x03, 0x22, 0x02, 0x01)
    fun enableSpo2() = byteArrayOf(0x2f, 0x03, 0x22, 0x04, 0x01)

    // --- Autenticazione AES --------------------------------------------------

    /** Cifra il nonce di 15 byte con AES/ECB/PKCS5Padding → 16 byte. */
    fun encryptNonce(authKey: ByteArray, nonce15: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(authKey, "AES"))
        return cipher.doFinal(nonce15) // 15 byte + padding PKCS5 → 16 byte
    }

    /** Genera una chiave di 16 byte come fa l'app ufficiale (UUID random, LE). */
    fun generateAuthKey(): ByteArray {
        val uuid = UUID.randomUUID()
        val out = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 0 until 8) { out[i] = (msb and 0xff).toByte(); msb = msb shr 8 }
        for (i in 0 until 8) { out[8 + i] = (lsb and 0xff).toByte(); lsb = lsb shr 8 }
        return out
    }

    // --- Parsing risposte ----------------------------------------------------

    /** Estrae il nonce di 15 byte dalla risposta 2f 10 2c <nonce>. */
    fun parseNonce(resp: ByteArray): ByteArray? {
        if (resp.size < 18) return null
        if (resp[0] != 0x2f.toByte() || resp[2] != 0x2c.toByte()) return null
        return resp.copyOfRange(3, 18)
    }

    /** true se la risposta di Authenticate indica successo (2f 02 2e 00). */
    fun authOk(resp: ByteArray): Boolean =
        resp.size >= 4 && resp[0] == 0x2f.toByte() && resp[2] == 0x2e.toByte() && resp[3] == 0x00.toByte()

    /** true se SetAuthKey ha avuto successo (25 xx 00, oppure 05 = test di fabbrica assenti). */
    fun setKeyOk(resp: ByteArray): Boolean {
        if (resp.size < 3 || resp[0] != 0x25.toByte()) return false
        return resp[2] == 0x00.toByte() || resp[2] == 0x05.toByte()
    }

    /** true se la risposta è il "serve app-auth" (2f 02 2f 01). */
    fun needsAuth(resp: ByteArray): Boolean =
        resp.size >= 4 && resp[0] == 0x2f.toByte() && resp[2] == 0x2f.toByte() && resp[3] == 0x01.toByte()

    data class DeviceInfo(val firmware: String, val bootloader: String, val api: String, val mac: String)

    /** Risposta firmware: 09 12 <02 00 00> <fw a.b.c> <bl a.b.c> <api a.b.c> ... <mac 6>. */
    fun parseFirmware(resp: ByteArray): DeviceInfo? {
        // Esempio Horizon: 09 12 02 00 00 03 04 03 01 00 01 05 00 0c ff ee dd cc bb aa
        if (resp.size < 20 || resp[0] != 0x09.toByte()) return null
        val p = resp.copyOfRange(2, resp.size)
        fun v3(off: Int) = "${p[off].toInt() and 0xff}.${p[off + 1].toInt() and 0xff}.${p[off + 2].toInt() and 0xff}"
        // Ordine osservato: api(0..3), firmware(3..6), bootloader(6..9), bt(9..12), poi MAC.
        val api = v3(0)
        val fw = v3(3)
        val bl = v3(6)
        val macBytes = p.copyOfRange(p.size - 6, p.size)
        val mac = macBytes.reversed().joinToString(":") { "%02x".format(it) }
        return DeviceInfo(fw, bl, api, mac)
    }

    data class Battery(val percent: Int, val charging: Int)
    fun parseBattery(resp: ByteArray): Battery? {
        if (resp.size < 3 || resp[0] != 0x0d.toByte()) return null
        return Battery(resp[2].toInt() and 0xff, if (resp.size > 3) resp[3].toInt() and 0xff else 0)
    }

    /** Riepilogo drain legacy (0x11): events(u16), progress(u8), bytes_left(u32). */
    data class DrainSummary(val events: Int, val bytesLeft: Long, val extended: Boolean)

    fun parseLegacySummary(resp: ByteArray): DrainSummary? {
        // 11 08 <events u16 LE> <progress u8> <bytes_left u32 LE> ... (osservato 11 08 08 00 9e0e0000 0300)
        if (resp.size < 8 || resp[0] != 0x11.toByte()) return null
        val events = (resp[2].toInt() and 0xff) or ((resp[3].toInt() and 0xff) shl 8)
        val bytesLeft = ((resp[5].toInt() and 0xff).toLong()) or
            ((resp[6].toInt() and 0xff).toLong() shl 8) or
            ((resp[7].toInt() and 0xff).toLong() shl 16) or
            (if (resp.size > 8) (resp[8].toInt() and 0xff).toLong() shl 24 else 0L)
        return DrainSummary(events, bytesLeft, false)
    }

    /** Riepilogo drain esteso (0x42): events(u16), progress(u8), bytes_left(u32), buffer, result. */
    fun parseExtendedSummary(resp: ByteArray): DrainSummary? {
        // 2f .. 42 <events u16> <progress u8> <bytes_left u32> <buffer u8> <result u8>
        if (resp.size < 11 || resp[0] != 0x2f.toByte() || resp[2] != 0x42.toByte()) return null
        val events = (resp[3].toInt() and 0xff) or ((resp[4].toInt() and 0xff) shl 8)
        val bytesLeft = ((resp[6].toInt() and 0xff).toLong()) or
            ((resp[7].toInt() and 0xff).toLong() shl 8) or
            ((resp[8].toInt() and 0xff).toLong() shl 16) or
            ((resp[9].toInt() and 0xff).toLong() shl 24)
        return DrainSummary(events, bytesLeft, true)
    }
}
