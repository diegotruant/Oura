package it.anello.app.dev

import it.anello.app.data.DailySummary
import it.anello.app.data.Sample
import it.anello.app.data.SleepStage
import it.anello.app.data.StageSpan
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Protocollo degli anelli Colmi (R02, R03, R06, R09, R10 e simili venduti con altri nomi).
 *
 * Fonti pubbliche: tahnok/colmi_r02_client (MIT) per comandi, passi e battito; la documentazione
 * Puxtril/colmi-docs per il sonno ("Big Data"). Non richiede chiavi né account.
 *
 * Pacchetto: 16 byte, [0] = comando, fino a 14 byte di dati, [15] = somma dei primi 15 modulo 256.
 * L'orologio dell'anello viene impostato in UTC, quindi date e orari dei dati sono in UTC.
 */
object Colmi {
    val UART_SERVICE: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    val UART_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val UART_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val BIG_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
    val BIG_WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
    val BIG_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

    const val CMD_SET_TIME = 1
    const val CMD_BATTERY = 3
    const val CMD_HR_LOG = 21
    const val CMD_HR_SETTINGS = 22
    const val CMD_STEPS = 67
    const val BIG_SLEEP = 39

    private fun u8(p: ByteArray, i: Int): Int = p[i].toInt() and 0xff
    private fun bcd(v: Int): Byte = (((v / 10) shl 4) or (v % 10)).toByte()
    private fun unbcd(b: Int): Int = ((b shr 4) and 15) * 10 + (b and 15)

    fun checksum(p: ByteArray): Byte {
        var sum = 0
        for (i in 0 until minOf(15, p.size)) sum += u8(p, i)
        return (sum and 0xff).toByte()
    }

    fun packet(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= 14) { "al massimo 14 byte di dati" }
        val p = ByteArray(16)
        p[0] = cmd.toByte()
        data.copyInto(p, 1)
        p[15] = checksum(p)
        return p
    }

    fun valid(p: ByteArray): Boolean = p.size == 16 && checksum(p) == p[15]

    /** Imposta l'orologio dell'anello (orario UTC). L'ultimo byte è la lingua: 1 = inglese. */
    fun setTime(utc: LocalDateTime): ByteArray = packet(
        CMD_SET_TIME,
        byteArrayOf(bcd(utc.year % 100), bcd(utc.monthValue), bcd(utc.dayOfMonth), bcd(utc.hour), bcd(utc.minute), bcd(utc.second), 1),
    )

    fun battery(): ByteArray = packet(CMD_BATTERY)

    /** Risposta alla batteria: percentuale e se è in carica. */
    fun parseBattery(p: ByteArray): Pair<Int, Boolean>? =
        if (p.size == 16 && u8(p, 0) == CMD_BATTERY) Pair(u8(p, 1), u8(p, 2) != 0) else null

    /** Passi del giorno [dayOffset] giorni fa (0 = oggi, secondo l'orologio dell'anello). */
    fun readSteps(dayOffset: Int): ByteArray =
        packet(CMD_STEPS, byteArrayOf(dayOffset.toByte(), 0x0f, 0x00, 0x5f, 0x01))

    /** Registro del battito del giorno che inizia a [utcMidnight] (secondi Unix). */
    fun readHrLog(utcMidnight: Long): ByteArray = packet(
        CMD_HR_LOG,
        byteArrayOf(utcMidnight.toByte(), (utcMidnight shr 8).toByte(), (utcMidnight shr 16).toByte(), (utcMidnight shr 24).toByte()),
    )

    fun readHrSettings(): ByteArray = packet(CMD_HR_SETTINGS, byteArrayOf(1))

    /** Attiva o spegne la misura periodica del battito, ogni [intervalMin] minuti. */
    fun setHrSettings(enabled: Boolean, intervalMin: Int): ByteArray =
        packet(CMD_HR_SETTINGS, byteArrayOf(2, if (enabled) 1 else 2, intervalMin.toByte()))

    /** Risposta alle impostazioni del battito: attiva e intervallo in minuti. */
    fun parseHrSettings(p: ByteArray): Pair<Boolean, Int>? =
        if (p.size == 16 && u8(p, 0) == CMD_HR_SETTINGS) Pair(u8(p, 2) == 1, u8(p, 3)) else null

    fun bigRequest(id: Int): ByteArray = byteArrayOf(188.toByte(), id.toByte(), 0, 0, 0xff.toByte(), 0xff.toByte())

    // --- Passi ----------------------------------------------------------------------------

    /** Un intervallo di 15 minuti: [slot] = numero dell'intervallo nel giorno (0..95), orario UTC. */
    data class StepSlot(val date: LocalDate, val slot: Int, val steps: Int, val calories: Int, val distanceM: Int) {
        val startMs: Long get() = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + slot * 900_000L
    }

    /** Ricompone la risposta ai passi, che arriva in più pacchetti. */
    class StepsParser {
        private var index = 0
        private var newCalories = false
        private val slots = ArrayList<StepSlot>()
        var noData = false
            private set

        /** Lista completa quando è arrivato l'ultimo pacchetto, lista vuota se non ci sono dati, null se ne servono altri. */
        fun feed(p: ByteArray): List<StepSlot>? {
            if (p.size != 16 || u8(p, 0) != CMD_STEPS) return null
            if (index == 0 && u8(p, 1) == 255) { noData = true; return emptyList() }
            if (index == 0 && u8(p, 1) == 240) {
                newCalories = u8(p, 3) == 1
                index++
                return null
            }
            val date = runCatching { LocalDate.of(2000 + unbcd(u8(p, 1)), unbcd(u8(p, 2)), unbcd(u8(p, 3))) }.getOrNull()
            var cal = u8(p, 7) or (u8(p, 8) shl 8)
            if (newCalories) cal *= 10
            if (date != null) {
                slots += StepSlot(date, u8(p, 4), u8(p, 9) or (u8(p, 10) shl 8), cal, u8(p, 11) or (u8(p, 12) shl 8))
            }
            index++
            return if (u8(p, 5) == u8(p, 6) - 1) slots.toList() else null
        }

        /** Quello che è arrivato finora (se la risposta si interrompe). */
        fun partial(): List<StepSlot> = slots.toList()
    }

    // --- Registro del battito ---------------------------------------------------------------

    /** Ricompone il registro del battito di un giorno: 288 valori, uno ogni 5 minuti; 0 = nessuna misura. */
    class HrLogParser {
        private var raw = IntArray(0)
        private var size = 0
        private var index = 0
        var timestamp: Long? = null
            private set
        var noData = false
            private set

        /** true quando il registro è completo (o l'anello ha risposto che non ci sono dati). */
        fun feed(p: ByteArray): Boolean {
            if (p.size != 16 || u8(p, 0) != CMD_HR_LOG) return false
            val sub = u8(p, 1)
            when {
                sub == 255 -> { noData = true; return true }
                sub == 0 -> {
                    size = u8(p, 2)
                    raw = IntArray(maxOf(size * 13, 288) + 13)
                    index = 0
                    return false
                }
                sub == 1 -> {
                    timestamp = (u8(p, 2).toLong() or (u8(p, 3).toLong() shl 8) or (u8(p, 4).toLong() shl 16) or (u8(p, 5).toLong() shl 24))
                    if (raw.isEmpty()) raw = IntArray(288 + 13)
                    for (k in 0 until 9) raw[k] = u8(p, 6 + k)
                    index = 9
                    return false
                }
                else -> {
                    if (raw.isEmpty()) return false
                    for (k in 0 until 13) if (index + k < raw.size) raw[index + k] = u8(p, 2 + k)
                    index += 13
                    return sub == size - 1
                }
            }
        }

        /** I campioni validi (battito > 0) con il loro orario; scarta quelli nel futuro. */
        fun samples(nowMs: Long = System.currentTimeMillis()): List<Sample> {
            val ts = timestamp ?: return emptyList()
            val out = ArrayList<Sample>()
            for (k in 0 until minOf(288, index, raw.size)) {
                val t = ts * 1000 + k * 300_000L
                if (raw[k] in 30..220 && t <= nowMs) out += Sample(t, raw[k].toDouble())
            }
            return out
        }
    }

    // --- Sonno ("Big Data") -------------------------------------------------------------------

    /** Ricompone una risposta Big Data: intestazione di 6 byte (188, id, lunghezza u16, crc u16) e dati. */
    class BigData {
        private var buf = ByteArray(0)

        /** Coppia (id, dati) quando la risposta è completa. */
        fun feed(chunk: ByteArray): Pair<Int, ByteArray>? {
            if (buf.isEmpty() && (chunk.isEmpty() || u8(chunk, 0) != 188)) return null
            buf += chunk
            if (buf.size < 6) return null
            val len = u8(buf, 2) or (u8(buf, 3) shl 8)
            if (buf.size < 6 + len) return null
            val out = Pair(u8(buf, 1), buf.copyOfRange(6, 6 + len))
            buf = ByteArray(0)
            return out
        }
    }

    const val SLEEP_LIGHT = 2
    const val SLEEP_DEEP = 3
    const val SLEEP_AWAKE = 5

    /** Una notte registrata dall'anello: inizio, fine e periodi (tipo, minuti) in ordine. */
    data class SleepNight(val startMs: Long, val endMs: Long, val periods: List<Pair<Int, Int>>)

    /**
     * Dati del sonno: numero di giorni, poi per ogni giorno "giorni fa", lunghezza in byte,
     * inizio e fine in minuti dopo la mezzanotte (se l'inizio è maggiore della fine, è la sera prima),
     * poi coppie (tipo, minuti).
     */
    fun parseSleep(d: ByteArray, nowMs: Long = System.currentTimeMillis()): List<SleepNight> {
        if (d.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        val out = ArrayList<SleepNight>()
        var i = 1
        repeat(u8(d, 0)) {
            if (i + 6 > d.size) return out
            val daysAgo = u8(d, i)
            val bytes = u8(d, i + 1)
            val start = u8(d, i + 2) or (u8(d, i + 3) shl 8)
            val end = u8(d, i + 4) or (u8(d, i + 5) shl 8)
            val periods = ArrayList<Pair<Int, Int>>()
            var k = i + 6
            val stop = minOf(i + 2 + bytes, d.size)
            while (k + 1 < stop) { periods += Pair(u8(d, k), u8(d, k + 1)); k += 2 }
            val midnight = today.minusDays(daysAgo.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val endMs = midnight + end * 60_000L
            val startMs = midnight + start * 60_000L - (if (start > end) 86_400_000L else 0L)
            if (start < 1440 && end < 1440 && endMs > startMs) out += SleepNight(startMs, endMs, periods)
            i += 2 + bytes
        }
        return out
    }

    // --- Dai dati dell'anello ai riepiloghi giornalieri ---------------------------------------

    /**
     * Costruisce i giorni: passi e calorie per giorno locale, notte dal sonno dell'anello con
     * il battito del registro. Se l'anello non ha dato il sonno, la notte viene stimata da
     * immobilità e calo del battito (vedi [SleepEstimator]) e non ha fasi.
     */
    fun buildDays(
        slots: List<StepSlot>,
        hr: List<Sample>,
        sleeps: List<SleepNight>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailySummary> {
        fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        val nights: Map<LocalDate, DailySummary> = if (sleeps.isNotEmpty()) {
            sleeps.map { n -> night(n, hr, dayOf(n.endMs)) }.groupBy { it.date }.mapValues { (_, l) -> l.maxBy { it.inBedMin } }
        } else {
            val moving = slots.filter { it.steps > 0 }.map { it.startMs / 900_000L }.toSet()
            val epochs = hr.map { Epoch(it.tMs, it.v.toInt(), still = (it.tMs / 900_000L) !in moving) }
            DeviceNights.build(epochs, 300_000L, zone).associateBy { it.date }
        }

        val activity = slots.groupBy { dayOf(it.startMs) }
        return (nights.keys + activity.keys).distinct().sorted().map { date ->
            val base = nights[date] ?: DailySummary(date)
            val a = activity[date]
            if (a == null) base else base.copy(
                steps = a.sumOf { it.steps }.toLong(),
                activeKcal = a.sumOf { it.calories } / 1000.0,
            )
        }
    }

    private fun night(n: SleepNight, hr: List<Sample>, date: LocalDate): DailySummary {
        val spans = ArrayList<StageSpan>()
        var t = n.startMs
        var light = 0; var deep = 0; var awake = 0
        for ((type, minutes) in n.periods) {
            val end = t + minutes * 60_000L
            val stage = when (type) {
                SLEEP_LIGHT -> { light += minutes; SleepStage.LIGHT }
                SLEEP_DEEP -> { deep += minutes; SleepStage.DEEP }
                SLEEP_AWAKE -> { awake += minutes; SleepStage.AWAKE }
                else -> null
            }
            if (stage != null) spans += StageSpan(t, end, stage)
            t = end
        }
        val inBed = ((n.endMs - n.startMs) / 60_000L).toInt()
        val asleep = if (spans.isEmpty()) inBed else light + deep
        val nightHr = hr.filter { it.tMs in n.startMs..n.endMs }
        val v = nightHr.map { it.v }
        val lowest = if (v.size >= 2) (0..v.size - 2).minOf { (v[it] + v[it + 1]) / 2 } else v.minOrNull()
        return DailySummary(
            date = date,
            sleepStartMs = n.startMs,
            sleepEndMs = n.endMs,
            inBedMin = inBed,
            asleepMin = asleep,
            deepMin = deep,
            lightMin = light,
            awakeMin = if (spans.isEmpty()) 0 else (inBed - asleep).coerceAtLeast(0),
            lowestHr = lowest,
            avgSleepHr = if (v.isEmpty()) null else v.average(),
            restingHr = lowest,
            hypnogram = spans,
            hrSeries = nightHr,
        )
    }
}
