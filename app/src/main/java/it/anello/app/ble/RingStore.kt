package it.anello.app.ble

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persistenza della parte BLE: chiave di autenticazione, cursore di sync e log
 * grezzo degli eventi. Tutto in locale, nella memoria privata dell'app.
 */
class RingStore(context: Context) {
    private val prefs = context.getSharedPreferences("ring_ble", Context.MODE_PRIVATE)
    private val rawFile = File(context.filesDir, "ring_events.jsonl")

    // --- chiave di autenticazione (per dispositivo, in esadecimale) ----------

    // L'anello usa indirizzi Bluetooth che possono cambiare (RPA): se non troviamo la
    // chiave per l'indirizzo attuale usiamo l'ultima salvata (app per un solo anello).
    fun keyHex(address: String): String? =
        prefs.getString("key_$address", null) ?: prefs.getString("key_last", null)

    fun keyFor(address: String): ByteArray? = keyHex(address)?.let { hexToBytes(it) }

    fun saveKey(address: String, key: ByteArray) {
        val h = bytesToHex(key)
        prefs.edit().putString("key_$address", h).putString("key_last", h).apply()
    }

    // --- cursore incrementale (decisecondi), unico per l'anello -------------

    fun cursor(@Suppress("UNUSED_PARAMETER") address: String): Long = prefs.getLong("cursor", 0L)
    fun setCursor(@Suppress("UNUSED_PARAMETER") address: String, value: Long) {
        prefs.edit().putLong("cursor", value).apply()
    }

    // --- ultimo anello selezionato ----------------------------------------

    var lastAddress: String?
        get() = prefs.getString("last_addr", null)
        set(v) { prefs.edit().putString("last_addr", v).apply() }

    // --- ultima batteria letta ---------------------------------------------

    fun saveBattery(percent: Int) {
        prefs.edit().putInt("bat_pct", percent).putLong("bat_time", System.currentTimeMillis()).apply()
    }
    fun lastBattery(): Pair<Int, Long>? =
        if (prefs.contains("bat_pct")) prefs.getInt("bat_pct", 0) to prefs.getLong("bat_time", 0) else null

    // --- log grezzo degli eventi ---------------------------------------------

    @Synchronized
    fun appendEvents(address: String, events: List<OuraEvents.Event>) {
        if (events.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        rawFile.appendText(buildString {
            for (e in events) {
                val o = JSONObject()
                o.put("addr", address)
                o.put("tag", e.tag)
                o.put("name", e.name)
                o.put("ts", e.ringDecis)
                o.put("body", bytesToHex(e.body))
                o.put("cap", now)
                append(o.toString()); append('\n')
            }
        })
    }

    fun rawCount(): Int = if (rawFile.exists()) rawFile.readLines().count { it.isNotBlank() } else 0

    fun loadRaw(): List<OuraEvents.Event> {
        if (!rawFile.exists()) return emptyList()
        return rawFile.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching {
                val o = JSONObject(line)
                OuraEvents.Event(o.getInt("tag"), o.getString("name"), o.getLong("ts"), hexToBytes(o.getString("body")))
            }.getOrNull()
        }
    }

    fun rawFilePath(): String = rawFile.absolutePath

    fun clearRaw() { if (rawFile.exists()) rawFile.delete() }

    companion object {
        fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        fun hexToBytes(s: String): ByteArray {
            val clean = s.trim()
            return ByteArray(clean.length / 2) {
                ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
            }
        }
    }
}

/** Sintesi decodificata degli eventi catturati, per mostrare qualcosa subito. */
data class RingAggregate(
    val totalEvents: Int,
    val eventCounts: Map<String, Int>,
    val stageMinutes: Map<String, Int>,   // profondo/leggero/rem/sveglio
    val hrvAvg: Double?,
    val hrvSamples: Int,
    val hrAvg: Double?,
    val tempAvg: Double?,
    val spo2Avg: Double?,
    val lastUnixAnchor: Long?,
) {
    companion object {
        fun from(events: List<OuraEvents.Event>): RingAggregate {
            val counts = events.groupingBy { it.name }.eachCount().toSortedMap()

            // Fasi: concatena tutti gli ipnogrammi disponibili (30 s per epoca).
            val stageCount = IntArray(4)
            events.filter { it.tag in intArrayOf(0x4b, 0x4e, 0x5a) }.forEach { e ->
                OuraEvents.decodeSleepPhases(e.body)?.forEach { p -> if (p in 0..3) stageCount[p]++ }
            }
            val stageMin = mapOf(
                "profondo" to stageCount[OuraEvents.PHASE_DEEP] / 2,
                "leggero" to stageCount[OuraEvents.PHASE_LIGHT] / 2,
                "rem" to stageCount[OuraEvents.PHASE_REM] / 2,
                "sveglio" to stageCount[OuraEvents.PHASE_AWAKE] / 2,
            )

            val rmssd = ArrayList<Int>()
            val sleepHr = ArrayList<Int>()
            events.filter { it.tag == 0x5d }.forEach { e ->
                OuraEvents.decodeHrv(e.body)?.let { (hr, rm) ->
                    hr.filter { it in 25..120 }.forEach { sleepHr.add(it) }
                    rm.filter { it in 5..250 }.forEach { rmssd.add(it) }
                }
            }
            // battito anche da IBI/green, come riserva
            events.filter { it.tag == 0x60 }.forEach { e -> OuraEvents.decodeIbiHr(e.body)?.forEach { if (it in 25..180) sleepHr.add(it) } }
            events.filter { it.tag == 0x80 }.forEach { e -> OuraEvents.decodeGreenIbiHr(e.body)?.forEach { if (it in 25..180) sleepHr.add(it) } }

            val temps = ArrayList<Double>()
            events.filter { it.tag in intArrayOf(0x46, 0x69, 0x75) }.forEach { e ->
                OuraEvents.decodeTemps(e.body)?.forEach { if (it in 20.0..42.0) temps.add(it) }
            }
            val spo2 = ArrayList<Int>()
            events.filter { it.tag == 0x6f }.forEach { e ->
                OuraEvents.decodeSpo2(e.body)?.forEach { if (it in 70..100) spo2.add(it) }
            }
            val anchor = events.filter { it.tag == 0x42 }
                .mapNotNull { OuraEvents.decodeTimeSyncUnix(it.body) }
                .maxOrNull()

            return RingAggregate(
                totalEvents = events.size,
                eventCounts = counts,
                stageMinutes = stageMin,
                hrvAvg = if (rmssd.isEmpty()) null else rmssd.average(),
                hrvSamples = rmssd.size,
                hrAvg = if (sleepHr.isEmpty()) null else sleepHr.average(),
                tempAvg = if (temps.isEmpty()) null else temps.average(),
                spo2Avg = if (spo2.isEmpty()) null else spo2.average(),
                lastUnixAnchor = anchor,
            )
        }
    }
}
