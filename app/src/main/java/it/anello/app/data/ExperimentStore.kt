package it.anello.app.data

import android.content.Context
import it.anello.app.score.Experiment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class ExperimentStore(context: Context) {
    private val file = File(context.filesDir, "experiments.json")

    @Synchronized
    fun load(): List<Experiment> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Experiment(o.getString("id"), o.getString("name"), LocalDate.parse(o.getString("start")),
                    o.getInt("phaseDays"), o.optInt("cycles", 1))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(all: List<Experiment>) {
        val arr = JSONArray()
        all.sortedBy { it.start }.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("name", e.name); put("start", e.start.toString())
                put("phaseDays", e.phaseDays); put("cycles", e.cycles)
            })
        }
        val tmp = File(file.parentFile, "experiments.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
