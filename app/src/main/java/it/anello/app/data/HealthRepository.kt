package it.anello.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthRepository(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** Diagnostica: quali app hanno scritto dati di sonno negli ultimi 30 giorni. */
    suspend fun sleepSources(): Map<String, Int> {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(30))
        return readAll(SleepSessionRecord::class, start, end, emptySet())
            .groupingBy { it.metadata.dataOrigin.packageName }
            .eachCount()
    }

    /**
     * Passi e calorie attive per giorno negli ultimi [days] giorni, da TUTTE le fonti
     * (telefono, app di fitness, app Oura): Health Connect elimina i doppioni secondo la
     * priorità delle app impostata dall'utente. Usato con la fonte "Anello diretto".
     */
    suspend fun fetchActivity(days: Int): Map<LocalDate, Pair<Long?, Double?>> {
        val today = LocalDate.now(ZoneId.systemDefault())
        val firstDay = today.minusDays(days.toLong() - 1)
        return client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(firstDay.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        ).associate {
            it.startTime.toLocalDate() to Pair(
                it.result[StepsRecord.COUNT_TOTAL],
                it.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            )
        }
    }

    /**
     * Legge da Health Connect gli ultimi [days] giorni e restituisce un riepilogo per giorno.
     */
    suspend fun fetchDays(days: Int, pkg: String): List<DailySummary> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = today.minusDays(days.toLong() - 1)
        val origins = setOf(DataOrigin(pkg))

        // Le notti che terminano nel primo giorno iniziano la sera prima.
        val start = firstDay.minusDays(1).atStartOfDay(zone).toInstant()
        val end = Instant.now()

        val sessions = readAll(SleepSessionRecord::class, start, end, origins)
        val hr = readAll(HeartRateRecord::class, start, end, origins)
            .flatMap { r -> r.samples.map { it.time to it.beatsPerMinute.toDouble() } }
            .sortedBy { it.first }
        val hrv = readAll(HeartRateVariabilityRmssdRecord::class, start, end, origins)
            .map { it.time to it.heartRateVariabilityMillis }
        val resting = readAll(RestingHeartRateRecord::class, start, end, origins)
            .map { it.time to it.beatsPerMinute.toDouble() }
        val spo2 = readAll(OxygenSaturationRecord::class, start, end, origins)
            .map { it.time to it.percentage.value }
        val resp = readAll(RespiratoryRateRecord::class, start, end, origins)
            .map { it.time to it.rate }

        val activity = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    firstDay.atStartOfDay(), today.plusDays(1).atStartOfDay()
                ),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = origins,
            )
        ).associateBy { it.startTime.toLocalDate() }

        // Notte principale = sessione più lunga che termina in quel giorno (i pisolini sono ignorati).
        val mainSleepByDay = sessions
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.maxBy { Duration.between(it.startTime, it.endTime) } }
            .filterValues { Duration.between(it.startTime, it.endTime).toMinutes() >= 120 }

        return (0 until days).map { offset ->
            val date = firstDay.plusDays(offset.toLong())
            val dayAgg = activity[date]
            var summary = DailySummary(
                date = date,
                steps = dayAgg?.result?.get(StepsRecord.COUNT_TOTAL),
                activeKcal = dayAgg?.result?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            )
            val s = mainSleepByDay[date]
            if (s != null) {
                val inWindow = { t: Instant -> !t.isBefore(s.startTime) && !t.isAfter(s.endTime) }
                val nightHr = hr.filter { inWindow(it.first) }
                val restingOfDay = resting.filter { it.first.atZone(zone).toLocalDate() == date }
                    .map { it.second }
                val lowest = lowestRollingAverage(nightHr, Duration.ofMinutes(5))
                summary = summary.withSleep(s).copy(
                    lowestHr = lowest,
                    avgSleepHr = nightHr.map { it.second }.averageOrNull(),
                    avgHrv = hrv.filter { inWindow(it.first) }.map { it.second }.averageOrNull(),
                    restingHr = restingOfDay.averageOrNull() ?: lowest,
                    spo2 = spo2.filter { inWindow(it.first) }.map { it.second }.averageOrNull(),
                    respiratoryRate = resp.filter { inWindow(it.first) }.map { it.second }.averageOrNull(),
                    hrSeries = DailySummary.bin(nightHr.map { Sample(it.first.toEpochMilli(), it.second) }),
                    hrvSeries = hrv.filter { inWindow(it.first) }.sortedBy { it.first }
                        .map { Sample(it.first.toEpochMilli(), it.second) },
                )
            }
            summary
        }
    }

    private fun DailySummary.withSleep(s: SleepSessionRecord): DailySummary {
        val inBed = Duration.between(s.startTime, s.endTime).toMinutes().toInt()
        val spans = s.stages.mapNotNull { st ->
            val stage = when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
                SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
                SleepSessionRecord.STAGE_TYPE_LIGHT,
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStage.LIGHT
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStage.AWAKE
                else -> null
            }
            stage?.let { StageSpan(st.startTime.toEpochMilli(), st.endTime.toEpochMilli(), it) }
        }.sortedBy { it.startMs }

        fun minutesOf(stage: SleepStage) =
            spans.filter { it.stage == stage }.sumOf { (it.endMs - it.startMs) / 60_000 }.toInt()

        val deep = minutesOf(SleepStage.DEEP)
        val rem = minutesOf(SleepStage.REM)
        val light = minutesOf(SleepStage.LIGHT)
        val awake = if (spans.isEmpty()) 0 else (inBed - deep - rem - light).coerceAtLeast(0)
        val asleep = if (spans.isEmpty()) inBed else deep + rem + light

        return copy(
            sleepStartMs = s.startTime.toEpochMilli(),
            sleepEndMs = s.endTime.toEpochMilli(),
            inBedMin = inBed,
            asleepMin = asleep,
            deepMin = deep,
            remMin = rem,
            lightMin = light,
            awakeMin = awake,
            hypnogram = spans,
        )
    }

    private suspend fun <T : Record> readAll(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        origins: Set<DataOrigin>,
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = origins,
                    pageToken = token,
                )
            )
            out += resp.records
            token = resp.pageToken
        } while (token != null)
        return out
    }
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

/** Minimo della media mobile su finestra temporale: stima robusta della FC più bassa della notte. */
private fun lowestRollingAverage(samples: List<Pair<Instant, Double>>, window: Duration): Double? {
    if (samples.isEmpty()) return null
    var best: Double? = null
    var lo = 0
    var sum = 0.0
    for (hi in samples.indices) {
        sum += samples[hi].second
        while (Duration.between(samples[lo].first, samples[hi].first) > window) {
            sum -= samples[lo].second
            lo++
        }
        val n = hi - lo + 1
        if (n >= 3) {
            val avg = sum / n
            if (best == null || avg < best) best = avg
        }
    }
    return best ?: samples.minOf { it.second }
}
