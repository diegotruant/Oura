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

    init {
        // Il primo anello associato (prima del supporto a più anelli) resta legato ai file esistenti.
        if (!prefs.contains("legacy_ring")) prefs.getString("last_addr", null)?.let {
            prefs.edit().putString("legacy_ring", it).apply()
        }
    }

    private val legacyRing: String? get() = prefs.getString("legacy_ring", null)

    // --- chiave di autenticazione, una per anello (esadecimale) --------------

    fun keyHex(address: String): String? = prefs.getString("key_$address", null)

    fun keyFor(address: String): ByteArray? = keyHex(address)?.let { hexToBytes(it) }

    fun saveKey(address: String, key: ByteArray) {
        prefs.edit().putString("key_$address", bytesToHex(key)).apply()
        if (legacyRing == null) prefs.edit().putString("legacy_ring", address).apply()
    }

    /** Indirizzi degli anelli associati (quelli con una chiave salvata). */
    fun pairedRings(): List<String> =
        prefs.all.keys.filter { it.startsWith("key_") && it != "key_last" }.map { it.removePrefix("key_") }.sorted()

    fun name(address: String): String? = prefs.getString("name_$address", null)
    fun saveName(address: String, name: String) { prefs.edit().putString("name_$address", name).apply() }

    // --- cursore incrementale (decisecondi), uno per anello -----------------

    fun cursor(address: String): Long =
        prefs.getLong("cursor_$address", if (address == legacyRing) prefs.getLong("cursor", 0L) else 0L)

    fun setCursor(address: String, value: Long) {
        prefs.edit().putLong("cursor_$address", value).apply()
    }

    // --- ultimo anello selezionato ----------------------------------------

    var lastAddress: String?
        get() = prefs.getString("last_addr", null)
        set(v) { prefs.edit().putString("last_addr", v).apply() }

    // --- sincronizzazione automatica ----------------------------------------

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(v) { prefs.edit().putBoolean("auto_sync", v).apply() }

    fun saveLastSync(message: String, ok: Boolean) {
        prefs.edit().putLong("sync_time", System.currentTimeMillis())
            .putString("sync_msg", message).putBoolean("sync_ok", ok).apply()
    }
    data class LastSync(val time: Long, val message: String, val ok: Boolean)
    fun lastSync(): LastSync? = if (!prefs.contains("sync_time")) null else
        LastSync(prefs.getLong("sync_time", 0), prefs.getString("sync_msg", "") ?: "", prefs.getBoolean("sync_ok", false))

    // --- ultima batteria letta ---------------------------------------------

    fun saveBattery(address: String, percent: Int) {
        prefs.edit().putInt("bat_pct_$address", percent).putLong("bat_time_$address", System.currentTimeMillis()).apply()
    }

    fun lastBattery(address: String?): Pair<Int, Long>? {
        address ?: return null
        if (prefs.contains("bat_pct_$address")) return prefs.getInt("bat_pct_$address", 0) to prefs.getLong("bat_time_$address", 0)
        return if (address == legacyRing && prefs.contains("bat_pct")) prefs.getInt("bat_pct", 0) to prefs.getLong("bat_time", 0) else null
    }

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

    /** Eventi grezzi di un anello ([address]) oppure di tutti (null). */
    fun loadRaw(address: String? = null): List<OuraEvents.Event> {
        if (!rawFile.exists()) return emptyList()
        return rawFile.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching {
                val o = JSONObject(line)
                if (address != null && o.optString("addr") != address) return@runCatching null
                OuraEvents.Event(o.getInt("tag"), o.getString("name"), o.getLong("ts"), hexToBytes(o.getString("body")))
            }.getOrNull()
        }
    }

    /** Tiene nel log grezzo solo gli eventi catturati negli ultimi [days] giorni. */
    @Synchronized
    fun pruneRaw(days: Int = 7) {
        if (!rawFile.exists()) return
        val cutoff = System.currentTimeMillis() / 1000 - days * 86_400L
        val lines = rawFile.readLines()
        val kept = lines.filter { line ->
            line.isNotBlank() && runCatching { JSONObject(line).getLong("cap") >= cutoff }.getOrDefault(true)
        }
        if (kept.size == lines.count { it.isNotBlank() }) return
        val tmp = File(rawFile.parentFile, "ring_events.jsonl.tmp")
        tmp.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
        tmp.renameTo(rawFile)
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
