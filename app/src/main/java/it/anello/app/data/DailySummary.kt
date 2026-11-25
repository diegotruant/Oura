package it.anello.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Fase del sonno nell'ipnogramma. */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

data class StageSpan(val startMs: Long, val endMs: Long, val stage: SleepStage)

/**
 * Riepilogo di una giornata. La notte viene assegnata al giorno del risveglio.
 * I campi nulli indicano che il dato non è arrivato da Health Connect.
 */
data class DailySummary(
    val date: LocalDate,
    val sleepStartMs: Long? = null,
    val sleepEndMs: Long? = null,
    val inBedMin: Int = 0,
    val asleepMin: Int = 0,
    val deepMin: Int = 0,
    val remMin: Int = 0,
    val lightMin: Int = 0,
    val awakeMin: Int = 0,
    val lowestHr: Double? = null,
    val avgSleepHr: Double? = null,
    val avgHrv: Double? = null,
    val restingHr: Double? = null,
    val spo2: Double? = null,
    val respiratoryRate: Double? = null,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val hypnogram: List<StageSpan> = emptyList(),
) {
    val hasSleep: Boolean get() = sleepStartMs != null && asleepMin > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date.toString())
        putOpt("sleepStartMs", sleepStartMs)
        putOpt("sleepEndMs", sleepEndMs)
        put("inBedMin", inBedMin)
        put("asleepMin", asleepMin)
        put("deepMin", deepMin)
        put("remMin", remMin)
        put("lightMin", lightMin)
        put("awakeMin", awakeMin)
        putOpt("lowestHr", lowestHr)
        putOpt("avgSleepHr", avgSleepHr)
        putOpt("avgHrv", avgHrv)
        putOpt("restingHr", restingHr)
        putOpt("spo2", spo2)
        putOpt("respiratoryRate", respiratoryRate)
        putOpt("steps", steps)
        putOpt("activeKcal", activeKcal)
        put("hypnogram", JSONArray().apply {
            hypnogram.forEach {
                put(JSONArray().put(it.startMs).put(it.endMs).put(it.stage.name))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): DailySummary {
            fun d(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
            fun l(k: String): Long? = if (o.has(k) && !o.isNull(k)) o.getLong(k) else null
            val hyp = o.optJSONArray("hypnogram")
            val spans = buildList {
                if (hyp != null) for (i in 0 until hyp.length()) {
                    val a = hyp.getJSONArray(i)
                    add(StageSpan(a.getLong(0), a.getLong(1), SleepStage.valueOf(a.getString(2))))
                }
            }
            return DailySummary(
                date = LocalDate.parse(o.getString("date")),
                sleepStartMs = l("sleepStartMs"),
                sleepEndMs = l("sleepEndMs"),
                inBedMin = o.optInt("inBedMin"),
                asleepMin = o.optInt("asleepMin"),
                deepMin = o.optInt("deepMin"),
                remMin = o.optInt("remMin"),
                lightMin = o.optInt("lightMin"),
                awakeMin = o.optInt("awakeMin"),
                lowestHr = d("lowestHr"),
                avgSleepHr = d("avgSleepHr"),
                avgHrv = d("avgHrv"),
                restingHr = d("restingHr"),
                spo2 = d("spo2"),
                respiratoryRate = d("respiratoryRate"),
                steps = l("steps"),
                activeKcal = d("activeKcal"),
                hypnogram = spans,
            )
        }
    }
}
