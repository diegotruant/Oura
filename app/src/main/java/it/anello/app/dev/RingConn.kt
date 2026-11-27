package it.anello.app.dev

import java.util.UUID

/**
 * Protocollo degli anelli RingConn (Gen 2 e Gen 3).
 *
 * Fonti pubbliche: zazaulola/ringlink (MIT) e OpenCircuit (MIT). Non richiede chiavi né account:
 * l'autenticazione dipende solo dall'indirizzo Bluetooth dell'anello.
 *
 * - I comandi si inviano così come sono, senza controllo, e finiscono con 0x00.
 * - Le risposte sono [id][dati…][xor], con id = comando xor 0x80; la fine dello storico (0x50) non ha xor.
 * - Lo storico si legge a pagine; confermare una pagina la CANCELLA dall'anello.
 */
object RingConn {
    val SERVICE: UUID = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437")
    val WRITE: UUID = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437")
    val NOTIFY: UUID = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437")

    val HELLO = byteArrayOf(0x01, 0x00, 0x00)
    val FETCH = byteArrayOf(0x07, 0x00, 0x00)
    val STATUS_QUERY = byteArrayOf(0xd0.toByte(), 0x00, 0x00)
    val HEARTBEAT_ACK = byteArrayOf(0x91.toByte(), 0x00, 0x00)
    val ACK_47 = byteArrayOf(0xc7.toByte(), 0x00, 0x00)
    val ACK_4C = byteArrayOf(0xcc.toByte(), 0x00, 0x00)
    val ACK_4D = byteArrayOf(0xcd.toByte(), 0x00, 0x00)

    const val RESP_STATUS = 0x81
    const val RESP_SYNC_OPEN = 0x82
    const val RESP_DESCRIPTOR_FETCH = 0x87
    const val RESP_DESCRIPTOR_QUERY = 0x10
    const val RESP_HEARTBEAT = 0x11
    const val RESP_PAGE_47 = 0x47
    const val RESP_PAGE_4C = 0x4c
    const val RESP_PAGE_4D = 0x4d
    const val RESP_END = 0x50

    const val CHANNEL_SLEEP = 0x00
    const val CHANNEL_ALL_DAY = 0x03

    /** I contatori dei record partono da qui (secondi Unix); il cursore del comando di apertura da [CURSOR_EPOCH]. */
    const val RECORD_EPOCH = 1_577_793_600L
    const val CURSOR_EPOCH = 1_577_808_000L

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

    fun valid(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        if (u8(frame, 0) == RESP_END) return true
        if (frame.size < 2) return false
        var acc = 0
        for (i in 0 until frame.size - 1) acc = acc xor u8(frame, i)
        return acc == u8(frame, frame.size - 1)
    }

    // --- Autenticazione -----------------------------------------------------------------------

    fun macBytes(address: String): ByteArray {
        val parts = address.trim().split(':')
        require(parts.size == 6) { "indirizzo non valido: $address" }
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }

    fun keyByte(mac: ByteArray): Int = u8(mac, 3) xor u8(mac, 4) xor u8(mac, 5)

    /** Risposta alla sfida: ultimi 3 byte di SM3(chiave, sfida). */
    fun authResponse(mac: ByteArray, challenge: Int): ByteArray =
        Sm3.hash(byteArrayOf(keyByte(mac).toByte(), challenge.toByte())).copyOfRange(29, 32)

    /** Comando di risposta a un frame di stato "81 00 <sfida> <xor>". */
    fun authReply(statusFrame: ByteArray, address: String): ByteArray? {
        if (statusFrame.size < 3) return null
        val r = authResponse(macBytes(address), u8(statusFrame, 2))
        return byteArrayOf(0x01, 0x01, r[0], r[1], r[2], 0x00)
    }

    /** Apre la lettura di un canale dello storico. */
    fun syncOpen(nowUnix: Long, channel: Int): ByteArray {
        val c = nowUnix - CURSOR_EPOCH
        return byteArrayOf(0x02, 0x00, (c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte(), channel.toByte(), 0x01, 0x00)
    }

    // --- Stato dell'anello --------------------------------------------------------------------

    /** Descrittore di 19 byte (0x10 o 0x87): batteria, passi del giorno, temperatura della pelle. */
    data class Descriptor(
        val battery: Int,
        val charging: Boolean,
        val steps: Int,
        val skinTempA: Double,
        val skinTempB: Double,
        val batteryMv: Int,
    )

    fun parseDescriptor(f: ByteArray): Descriptor? {
        if (f.size < 19) return null
        val id = u8(f, 0)
        if (id != RESP_DESCRIPTOR_FETCH && id != RESP_DESCRIPTOR_QUERY) return null
        return Descriptor(u8(f, 1), u8(f, 2) == 0x04, u16(f, 4), u16(f, 6) / 10.0, u16(f, 8) / 10.0, u16(f, 14))
    }

    // --- Storico: un record ogni 2,5 minuti ---------------------------------------------------

    const val EPOCH_SECONDS = 150L
    private const val RECORD_LEN = 23

    data class Record(
        val unix: Long,
        val hr: Int?,
        val hrv: Int?,
        val resp: Double?,
        val spo2: Int?,
        val motion: Int,
    )

    /**
     * Record di 23 byte: contatore u32, battito, HRV (RMSSD), affidabilità, respiro ×8, SpO2
     * (oppure un'etichetta 0x11–0x13 nei record di attività), cinque conteggi di movimento da 30 s.
     * Il battito sotto 30 significa "non misurato". HRV, respiro e SpO2 valgono solo nei record a riposo.
     */
    fun parseRecord(r: ByteArray, off: Int): Record? {
        if (off + RECORD_LEN > r.size) return null
        fun b(i: Int) = u8(r, off + i)
        val idle = b(4) == 0x05 && b(5) == 0x00 && b(6) == 0x0c && b(7) == 0x00 && b(9) == 0x0a &&
            (10..14).all { b(it) == 0x01 } && (15..21).all { b(it) == 0x00 }
        val activity = b(8) in 0x11..0x13
        val vitals = !idle && !activity
        return Record(
            unix = u32(r, off) + RECORD_EPOCH,
            hr = if (!idle && b(4) >= 30) b(4) else null,
            hrv = if (vitals && b(5) in 1..250) b(5) else null,
            resp = if (vitals && b(7) > 0) b(7) / 8.0 else null,
            spo2 = if (vitals && b(8) in 70..100) b(8) else null,
            motion = (10..14).sumOf { b(it) },
        )
    }

    /** Record di una pagina 0x4c: 3 byte di intestazione, record da 23 byte, 1 byte di xor. */
    fun parsePage(page: ByteArray): List<Record> {
        val n = (page.size - 4) / RECORD_LEN
        return (0 until n).mapNotNull { parseRecord(page, 3 + it * RECORD_LEN) }
    }

    /** La mano è ferma quando i cinque conteggi sono al minimo (1 ciascuno). */
    fun toEpoch(r: Record): Epoch = Epoch(r.unix * 1000, r.hr, still = r.motion <= 6, hrv = r.hrv, resp = r.resp, spo2 = r.spo2)
}

/** SM3, la funzione di hash dello standard pubblico GB/T 32905-2016 (usata dall'autenticazione RingConn). */
object Sm3 {
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    private fun rotl(x: Int, n: Int): Int = Integer.rotateLeft(x, n)
    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)
    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)

    fun hash(msg: ByteArray): ByteArray {
        val bitLen = msg.size.toLong() * 8
        val padLen = ((msg.size + 9 + 63) / 64) * 64
        val m = ByteArray(padLen)
        msg.copyInto(m)
        m[msg.size] = 0x80.toByte()
        for (i in 0 until 8) m[padLen - 1 - i] = (bitLen ushr (8 * i)).toByte()

        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)
        for (block in 0 until padLen / 64) {
            val o = block * 64
            for (j in 0 until 16) {
                w[j] = ((m[o + 4 * j].toInt() and 0xff) shl 24) or ((m[o + 4 * j + 1].toInt() and 0xff) shl 16) or
                    ((m[o + 4 * j + 2].toInt() and 0xff) shl 8) or (m[o + 4 * j + 3].toInt() and 0xff)
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
            for (j in 0 until 64) {
                val t = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = rotl(rotl(a, 12) + e + rotl(t, j % 32), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }
            v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (k in 0 until 4) out[4 * i + k] = (v[i] ushr (24 - 8 * k)).toByte()
        return out
    }
}
