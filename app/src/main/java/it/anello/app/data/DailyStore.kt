package it.anello.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.time.LocalDate

/**
 * Archivio locale dei riepiloghi giornalieri (un file JSON nella memoria privata dell'app).
 * Health Connect di norma conserva/espone solo gli ultimi 30 giorni: qui teniamo tutto lo storico.
 */
class DailyStore(context: Context, private val fileName: String = "days.json") {
    private val file = File(context.filesDir, fileName)

    @Synchronized
    fun load(): Map<LocalDate, DailySummary> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length())
                .map { DailySummary.fromJson(arr.getJSONObject(it)) }
                .associateBy { it.date }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun upsert(days: Collection<DailySummary>) {
        val all = load().toMutableMap()
        days.forEach { all[it.date] = it }
        val arr = JSONArray()
        all.values.sortedBy { it.date }.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "$fileName.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** Esporta tutto lo storico in CSV. */
    fun toCsv(): String = buildString {
        appendLine("data,inizio_sonno,fine_sonno,a_letto_min,sonno_min,profondo_min,rem_min,leggero_min,sveglio_min,fc_min,fc_media_sonno,hrv,fc_riposo,spo2,respiro,passi,kcal_attive")
        load().values.sortedBy { it.date }.forEach { d ->
            appendLine(listOf(
                d.date, d.sleepStartMs ?: "", d.sleepEndMs ?: "", d.inBedMin, d.asleepMin,
                d.deepMin, d.remMin, d.lightMin, d.awakeMin, d.lowestHr ?: "", d.avgSleepHr ?: "",
                d.avgHrv ?: "", d.restingHr ?: "", d.spo2 ?: "", d.respiratoryRate ?: "",
                d.steps ?: "", d.activeKcal ?: "",
            ).joinToString(","))
        }
    }
}
