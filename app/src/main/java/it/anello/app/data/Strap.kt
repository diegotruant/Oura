package it.anello.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Una misura di HRV fatta con la fascia cardio. */
data class StrapMeasurement(
    val timeMs: Long,
    val date: LocalDate,
    val rmssd: Double,
    val meanHr: Double,
    val seconds: Int,
    val coverage: Double,
    val device: String,
)

class StrapStore(context: Context) {
    private val file = File(context.filesDir, "strap.json")
    private val prefs = context.getSharedPreferences("strap", Context.MODE_PRIVATE)

    var address: String?
        get() = prefs.getString("address", null)
        set(v) { prefs.edit().putString("address", v).apply() }
    var name: String?
        get() = prefs.getString("name", null)
        set(v) { prefs.edit().putString("name", v).apply() }

    @Synchronized
    fun load(): List<StrapMeasurement> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                StrapMeasurement(o.getLong("timeMs"), LocalDate.parse(o.getString("date")), o.getDouble("rmssd"),
                    o.getDouble("meanHr"), o.getInt("seconds"), o.getDouble("coverage"), o.optString("device"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(m: StrapMeasurement) = save(load() + m)

    @Synchronized
    fun remove(timeMs: Long) = save(load().filterNot { it.timeMs == timeMs })

    private fun save(all: List<StrapMeasurement>) {
        val arr = JSONArray()
        all.sortedBy { it.timeMs }.forEach { m ->
            arr.put(JSONObject().apply {
                put("timeMs", m.timeMs); put("date", m.date.toString()); put("rmssd", m.rmssd); put("meanHr", m.meanHr)
                put("seconds", m.seconds); put("coverage", m.coverage); put("device", m.device)
            })
        }
        val tmp = File(file.parentFile, "strap.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
