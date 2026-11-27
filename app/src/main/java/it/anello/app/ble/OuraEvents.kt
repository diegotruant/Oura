package it.anello.app.ble

/**
 * Decodifica degli eventi storici dell'anello. Ogni frame è tag|len|payload,
 * e il payload inizia con un timestamp di 4 byte LE in decisecondi (unità da 100 ms)
 * relativi all'avvio dell'anello, seguito dal corpo specifico dell'evento.
 *
 * I decoder qui sotto sono quelli VALIDATI in open_oura (ipnogramma, HRV, temperatura,
 * SpO2, battito, attività, time_sync). Gli altri eventi vengono conservati grezzi.
 */
object OuraEvents {

    data class Event(
        val tag: Int,
        val name: String,
        val ringDecis: Long,     // timestamp grezzo dell'anello (decisecondi)
        val body: ByteArray,
    )

    /** Un frame è un evento storico se il tag è >= 0x41. */
    fun isEventFrame(frame: ByteArray): Boolean {
        if (frame.size < 2) return false
        val tag = frame[0].toInt() and 0xff
        return tag >= 0x41
    }

    fun parse(frame: ByteArray): Event? {
        if (frame.size < 2) return null
        val tag = frame[0].toInt() and 0xff
        val len = frame[1].toInt() and 0xff
        val payload = frame.copyOfRange(2, minOf(2 + len, frame.size))
        val ts = if (payload.size >= 4)
            (payload[0].toInt() and 0xff).toLong() or
                ((payload[1].toInt() and 0xff).toLong() shl 8) or
                ((payload[2].toInt() and 0xff).toLong() shl 16) or
                ((payload[3].toInt() and 0xff).toLong() shl 24)
        else 0L
        val body = if (payload.size > 4) payload.copyOfRange(4, payload.size) else ByteArray(0)
        return Event(tag, eventName(tag), ts, body)
    }

    // --- Decoder dei corpi (sottoinsieme validato) --------------------------

    const val PHASE_DEEP = 0
    const val PHASE_LIGHT = 1
    const val PHASE_REM = 2
    const val PHASE_AWAKE = 3

    /** Ipnogramma: header byte poi codici di 2 bit (4 per byte, MSB per primo). */
    fun decodeSleepPhases(body: ByteArray): IntArray? {
        if (body.size < 2) return null
        val out = ArrayList<Int>((body.size - 1) * 4)
        for (k in 1 until body.size) {
            val b = body[k].toInt() and 0xff
            for (shift in intArrayOf(6, 4, 2, 0)) out.add((b shr shift) and 0x03)
        }
        return out.toIntArray()
    }

    /** hrv_event: coppie (hr bpm, rmssd ms), una ogni 5 minuti. */
    fun decodeHrv(body: ByteArray): Pair<IntArray, IntArray>? {
        if (body.isEmpty() || body.size % 2 != 0) return null
        val hr = IntArray(body.size / 2)
        val rmssd = IntArray(body.size / 2)
        for (i in hr.indices) {
            hr[i] = body[2 * i].toInt() and 0xff
            rmssd[i] = body[2 * i + 1].toInt() and 0xff
        }
        return hr to rmssd
    }

    /** Temperature: i16 LE in centigradi/100. */
    fun decodeTemps(body: ByteArray): DoubleArray? {
        if (body.isEmpty() || body.size % 2 != 0) return null
        val out = DoubleArray(body.size / 2)
        for (i in out.indices) {
            val centi = ((body[2 * i].toInt() and 0xff) or (body[2 * i + 1].toInt() shl 8)).toShort()
            val c = centi / 100.0
            if (c < -40.0 || c > 85.0) return null
            out[i] = c
        }
        return out
    }

    /** spo2_event: header byte poi una % per campione; 0xff finale = sentinella. */
    fun decodeSpo2(body: ByteArray): IntArray? {
        if (body.size < 2) return null
        var end = body.size
        if (body[end - 1] == 0xff.toByte()) end--
        if (end <= 1) return null
        return IntArray(end - 1) { body[1 + it].toInt() and 0xff }
    }

    /** ibi_and_amplitude (0x60): 14 byte → fino a 6 battiti. Ritorna HR plausibili. */
    fun decodeIbiHr(body: ByteArray): IntArray? {
        if (body.size != 14) return null
        val b = IntArray(14) { body[it].toInt() and 0xff }
        val ibi = intArrayOf(
            (b[6] and 1) or (b[0] shl 3) or ((b[12] shr 5) and 6),
            (b[7] and 1) or (b[1] shl 3) or ((b[12] shr 3) and 6),
            (b[8] and 1) or (b[2] shl 3) or ((b[12] shr 1) and 6),
            (b[9] and 1) or (b[3] shl 3) or ((b[12] and 3) shl 1),
            (b[10] and 1) or (b[4] shl 3) or ((b[13] shr 5) and 6),
            (b[11] and 1) or (b[5] shl 3) or ((b[13] shr 3) and 6),
        )
        return ibi.filter { it in 300..2000 }.map { 60000 / it }.toIntArray()
    }

    /** green_ibi_quality (0x80): HR da battiti di buona qualità. */
    fun decodeGreenIbiHr(body: ByteArray): IntArray? {
        if (body.size < 2) return null
        val hr = ArrayList<Int>()
        var i = 0
        while (i + 1 < body.size) {
            val b0 = body[i].toInt() and 0xff
            val b1 = body[i + 1].toInt() and 0xff
            val ibi = (b1 and 0x07) or (b0 shl 3)
            val q = (b1 shr 3) and 0x03
            if (q == 1 && ibi in 300..2000) hr.add(60000 / ibi)
            i += 2
        }
        return if (hr.isEmpty()) null else hr.toIntArray()
    }

    /** activity_information (0x50): state byte + livelli MET per bin. */
    fun decodeActivityMet(body: ByteArray): DoubleArray? {
        if (body.size < 2) return null
        val out = DoubleArray(body.size - 1)
        for (i in out.indices) {
            val v = body[i + 1].toInt() and 0xff
            out[i] = if (v < 0x80) v * 0.1 else 12.8 + (v - 128) * 0.2
        }
        return out
    }

    /** bedtime_period (0x76): inizio e fine del sonno in decisecondi dell'anello. */
    fun decodeBedtime(body: ByteArray): Pair<Long, Long>? {
        if (body.size < 8) return null
        fun u32(o: Int) = (body[o].toLong() and 0xff) or ((body[o + 1].toLong() and 0xff) shl 8) or
            ((body[o + 2].toLong() and 0xff) shl 16) or ((body[o + 3].toLong() and 0xff) shl 24)
        return u32(0) to u32(4)
    }

    data class SleepPeriod(val avgHr: Double, val breath: Double, val motion: Int, val state: Int)

    /** sleep_period_information_2 (0x6a): 10 byte, battito x0.5, respiro /8 (parser nativo). */
    fun decodeSleepPeriodInfo(body: ByteArray): SleepPeriod? {
        if (body.size < 10) return null
        val motion = body[6].toInt() and 0xff
        val state = body[7].toInt()
        if (motion >= 120 || state !in 0..2) return null
        return SleepPeriod((body[0].toInt() and 0xff) * 0.5, (body[4].toInt() and 0xff) / 8.0, motion, state)
    }

    /** time_sync (0x42): u32 LE unix seconds nel corpo → ancora per datare gli eventi. */
    fun decodeTimeSyncUnix(body: ByteArray): Long? {
        if (body.size < 4) return null
        return (body[0].toInt() and 0xff).toLong() or
            ((body[1].toInt() and 0xff).toLong() shl 8) or
            ((body[2].toInt() and 0xff).toLong() shl 16) or
            ((body[3].toInt() and 0xff).toLong() shl 24)
    }

    /**
     * real_steps_features (0x7e/0x7f): 14 byte con 14 campi quantizzati sull'andatura (layout
     * da open_oura; il significato dei singoli campi non è noto). Non contengono un numero di
     * passi: servono come ingresso a un modello, che va costruito e verificato sui nostri dati.
     */
    fun decodeStepFeatures(body: ByteArray): IntArray? {
        if (body.size != 14) return null
        val p = IntArray(14) { body[it].toInt() and 0xff }
        return intArrayOf(
            (p[3] shr 7) or (p[0] shl 1), p[1] shl 1, p[2] shl 1, p[3] and 0x7f, p[4], p[5], p[6], p[7],
            (p[11] shr 7) or (p[8] shl 1), p[9] shl 1, p[10] shl 1, p[11] and 0x7f, p[12], p[13],
        )
    }

    /** Converte un timestamp dell'anello (decisecondi) in secondi unix usando un time_sync. */
    fun unixAt(ringDecis: Long, anchorDecis: Long, anchorUnix: Long): Long =
        anchorUnix + Math.round((ringDecis - anchorDecis) / 10.0)

    fun eventName(tag: Int): String = when (tag) {
        0x41 -> "ring_start"
        0x42 -> "time_sync"
        0x43 -> "debug_event"
        0x45 -> "state_change"
        0x46 -> "temp_event"
        0x47 -> "motion_event"
        0x49 -> "sleep_summary_1"
        0x4b -> "sleep_phase_information"
        0x4c -> "sleep_summary_2"
        0x4e -> "sleep_phase_details"
        0x4f -> "sleep_summary_3"
        0x50 -> "activity_information"
        0x51 -> "activity_summary_1"
        0x52 -> "activity_summary_2"
        0x53 -> "wear_event"
        0x58 -> "sleep_summary_4"
        0x5a -> "sleep_phase_data"
        0x5b -> "battery_info"
        0x61 -> "debug_data"
        0x5d -> "hrv_event"
        0x60 -> "ibi_and_amplitude"
        0x69 -> "temp_period"
        0x6a -> "sleep_period_information_2"
        0x6f -> "spo2_event"
        0x72 -> "sleep_acm_period"
        0x75 -> "sleep_temp_event"
        0x76 -> "bedtime_period"
        0x7e -> "real_steps_features_1"
        0x7f -> "real_steps_features_2"
        0x80 -> "green_ibi_quality"
        0x8b -> "spo2_r_pi"
        else -> "evento_0x%02x".format(tag)
    }
}
