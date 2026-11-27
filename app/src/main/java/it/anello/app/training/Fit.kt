package it.anello.app.training

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lettore FIT minimale (formato pubblico del Garmin FIT SDK), trasposto da research/apse/apse/fit.py.
 * Estrae dai messaggi `record` orario, potenza e battito. Controlla i CRC di intestazione e file,
 * gestisce timestamp compressi, campi sviluppatore, endianness per messaggio e file concatenati.
 */
object Fit {
    class FitException(message: String) : Exception(message)

    data class Record(val timestamp: Long, val powerW: Int?, val heartRate: Int?)

    private const val RECORD = 20
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )
    const val FIT_EPOCH_UNIX = 631065600L

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            val b = data[i].toInt() and 0xff
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }

    private class FieldDef(val num: Int, val size: Int, val base: Int)
    private class MsgDef(val global: Int, val little: Boolean, val fields: List<FieldDef>, val devSize: Int)

    fun decode(data: ByteArray): List<Record> {
        val out = ArrayList<Record>()
        var pos = 0
        while (pos < data.size) {
            if (data.size - pos < 12) {
                if ((pos until data.size).any { data[it].toInt() != 0 }) throw FitException("dati residui non validi")
                break
            }
            pos = decodeOne(data, pos, out)
        }
        return out
    }

    private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xff

    private fun uint(d: ByteArray, off: Int, size: Int, little: Boolean): Long {
        val bb = ByteBuffer.wrap(d, off, size).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        return when (size) {
            1 -> (bb.get().toInt() and 0xff).toLong()
            2 -> (bb.short.toInt() and 0xffff).toLong()
            4 -> bb.int.toLong() and 0xffffffffL
            else -> -1
        }
    }

    private fun decodeOne(d: ByteArray, pos: Int, out: MutableList<Record>): Int {
        val hsize = u8(d, pos)
        if (hsize != 12 && hsize != 14) throw FitException("intestazione FIT non valida")
        if (pos + hsize > d.size || String(d, pos + 8, 4, Charsets.US_ASCII) != ".FIT") throw FitException("intestazione FIT non valida")
        val dataSize = uint(d, pos + 4, 4, true)
        if (hsize == 14) {
            val hcrc = uint(d, pos + 12, 2, true).toInt()
            if (hcrc != 0 && hcrc != crc16(d, pos, pos + 12)) throw FitException("CRC intestazione errato")
        }
        val endL = pos.toLong() + hsize + dataSize
        if (endL + 2 > d.size) throw FitException("file troncato")
        val end = endL.toInt()
        if (crc16(d, pos, end + 2) != 0) throw FitException("CRC del file errato")

        val defs = HashMap<Int, MsgDef>()
        var lastTs: Long? = null
        var p = pos + hsize
        while (p < end) {
            val h = u8(d, p); p++
            if (h and 0x80 != 0) { // timestamp compresso
                val local = (h shr 5) and 0x03
                val offset = (h and 0x1F).toLong()
                val base = lastTs ?: throw FitException("timestamp compresso senza riferimento")
                var ts = (base and 0x1FL.inv()) + offset
                if (offset < (base and 0x1FL)) ts += 0x20
                lastTs = ts
                p = readData(d, p, end, defs[local], out, ts).first
                continue
            }
            val local = h and 0x0F
            if (h and 0x40 != 0) { // definizione
                if (p + 5 > end) throw FitException("definizione troncata")
                val little = u8(d, p + 1) == 0
                val global = uint(d, p + 2, 2, little).toInt()
                val n = u8(d, p + 4)
                p += 5
                if (p + 3 * n > end) throw FitException("definizione troncata")
                val fields = (0 until n).map { FieldDef(u8(d, p + 3 * it), u8(d, p + 3 * it + 1), u8(d, p + 3 * it + 2)) }
                p += 3 * n
                var dev = 0
                if (h and 0x20 != 0) {
                    val nd = u8(d, p); p++
                    if (p + 3 * nd > end) throw FitException("definizione troncata")
                    for (k in 0 until nd) dev += u8(d, p + 3 * k + 1)
                    p += 3 * nd
                }
                defs[local] = MsgDef(global, little, fields, dev)
            } else {
                val (np, ts) = readData(d, p, end, defs[local], out, null)
                p = np
                if (ts != null) lastTs = ts
            }
        }
        if (p != end) throw FitException("lunghezza dei messaggi incoerente")
        return end + 2
    }

    private fun readData(
        d: ByteArray, start: Int, end: Int, def: MsgDef?, out: MutableList<Record>, compressedTs: Long?,
    ): Pair<Int, Long?> {
        def ?: throw FitException("messaggio dati senza definizione")
        var p = start
        var ts: Long? = null
        var power: Int? = null
        var hr: Int? = null
        for (f in def.fields) {
            if (p + f.size > end) throw FitException("messaggio troncato")
            val baseType = f.base and 0x1F
            when {
                f.num == 253 && f.size == 4 -> uint(d, p, 4, def.little).takeIf { it != 0xFFFFFFFFL }?.let { ts = it }
                def.global == RECORD && f.num == 7 && f.size == 2 && baseType == 0x04 ->
                    uint(d, p, 2, def.little).takeIf { it != 0xFFFFL }?.let { power = it.toInt() }
                def.global == RECORD && f.num == 3 && f.size == 1 ->
                    uint(d, p, 1, def.little).takeIf { it != 0xFFL }?.let { hr = it.toInt() }
            }
            p += f.size
        }
        p += def.devSize
        if (p > end) throw FitException("messaggio troncato")
        val recTs = compressedTs ?: ts
        if (def.global == RECORD && recTs != null) out += Record(recTs, power, hr)
        return p to ts
    }

    /**
     * Serie di potenza a 1 Hz dall'inizio alla fine. Secondi mancanti (pause, buchi) valgono 0 W.
     * Ritorna la serie e il numero di secondi riempiti.
     */
    fun powerSeries(records: List<Record>): Pair<DoubleArray, Int> {
        if (records.isEmpty()) return DoubleArray(0) to 0
        val t0 = records.minOf { it.timestamp }
        val t1 = records.maxOf { it.timestamp }
        if (t1 - t0 > 48 * 3600) throw FitException("attività più lunga di 48 ore")
        val series = DoubleArray((t1 - t0 + 1).toInt()) { Double.NaN }
        for (r in records) r.powerW?.let { series[(r.timestamp - t0).toInt()] = it.toDouble() }
        val filled = series.count { it.isNaN() }
        for (i in series.indices) if (series[i].isNaN()) series[i] = 0.0
        return series to filled
    }
}
