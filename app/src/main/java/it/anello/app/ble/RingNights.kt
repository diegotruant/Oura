package it.anello.app.ble

import it.anello.app.data.DailySummary
import it.anello.app.data.Sample
import it.anello.app.data.SleepStage
import it.anello.app.data.StageSpan
import java.time.Instant
import java.time.ZoneId

/**
 * Costruisce le notti (DailySummary) dagli eventi grezzi dell'anello.
 *
 * - Finestra del sonno: evento bedtime_period (0x76), due timestamp dell'anello.
 * - Orario reale: ancora più vicina tra gli eventi time_sync (0x42).
 * - Fasi: sleep_phase_data (0x5a), pagine numerate (primo byte = indice pagina),
 *   13 byte = 52 epoche da 30 s ciascuna, a partire dall'inizio della finestra.
 * - Battito e respiro: sleep_period_information_2 (0x6a), un campione ogni ~30 s.
 * - HRV: hrv_event (0x5d), coppie (battito, RMSSD) ogni 5 minuti.
 * - SpO2: spo2_event (0x6f). Temperatura: sleep_temp_event (0x75).
 *
 * La notte è assegnata al giorno del risveglio, come per Health Connect.
 */
object RingNights {

    private const val EPOCH_DS = 300L // 30 s in decisecondi

    data class Anchor(val ringDecis: Long, val unix: Long)

    fun build(events: List<OuraEvents.Event>, zone: ZoneId = ZoneId.systemDefault()): List<DailySummary> {
        val anchors = events.filter { it.tag == 0x42 }
            .mapNotNull { e -> OuraEvents.decodeTimeSyncUnix(e.body)?.let { Anchor(e.ringDecis, it) } }
        if (anchors.isEmpty()) return emptyList()

        fun toMs(ds: Long): Long {
            val a = anchors.minBy { kotlin.math.abs(it.ringDecis - ds) }
            return (a.unix * 1000) + (ds - a.ringDecis) * 100
        }

        val windows = events.filter { it.tag == 0x76 }.mapNotNull { e ->
            OuraEvents.decodeBedtime(e.body)?.let { (s, end) -> Triple(s, end, e.ringDecis) }
        }.filter { (s, e, _) -> e > s && (e - s) in 36_000L..(16 * 36_000L) } // tra 1 e 16 ore
            .distinctBy { it.first }

        return windows.map { (bs, be, emittedAt) ->
            buildNight(events, bs, be, emittedAt, ::toMs, zone)
        }.groupBy { it.date }
            .map { (_, list) -> list.maxBy { it.inBedMin } } // una notte principale al giorno
    }

    /**
     * Come [build], ma solo per le notti finite negli ultimi [days] giorni. Il log grezzo
     * conserva 7 giorni: una notte più vecchia potrebbe avere già perso parte dei suoi eventi
     * e, ricostruita a metà, sovrascriverebbe quella salvata correttamente.
     */
    fun buildRecent(events: List<OuraEvents.Event>, days: Long = 5, zone: ZoneId = ZoneId.systemDefault()): List<DailySummary> {
        val cutoff = java.time.LocalDate.now(zone).minusDays(days)
        return build(events, zone).filter { it.date >= cutoff }
    }

    private fun buildNight(
        events: List<OuraEvents.Event>,
        bs: Long, be: Long, emittedAt: Long,
        toMs: (Long) -> Long,
        zone: ZoneId,
    ): DailySummary {
        val inWindow = { e: OuraEvents.Event -> e.ringDecis in bs..be }

        // --- Fasi: pagine emesse subito dopo la fine della finestra
        val pages = events.filter {
            it.tag in intArrayOf(0x5a, 0x4e, 0x4b) && it.ringDecis in emittedAt - 600..emittedAt + 36_000 &&
                it.body.size >= 2
        }
        val preferred = pages.filter { it.tag == 0x5a }.ifEmpty { pages }
        val byIndex = preferred.associateBy { it.body[0].toInt() and 0xff } // l'ultima vince
        val phases = ArrayList<Int>()
        byIndex.keys.sorted().forEach { idx ->
            OuraEvents.decodeSleepPhases(byIndex.getValue(idx).body)?.let { phases.addAll(it.toList()) }
        }
        val nEpochs = ((be - bs) / EPOCH_DS).toInt()
        val hyp = phases.take(nEpochs)

        val spans = ArrayList<StageSpan>()
        var i = 0
        while (i < hyp.size) {
            var j = i
            while (j + 1 < hyp.size && hyp[j + 1] == hyp[i]) j++
            spans += StageSpan(
                toMs(bs + i * EPOCH_DS), toMs(bs + (j + 1) * EPOCH_DS),
                when (hyp[i]) {
                    OuraEvents.PHASE_DEEP -> SleepStage.DEEP
                    OuraEvents.PHASE_REM -> SleepStage.REM
                    OuraEvents.PHASE_LIGHT -> SleepStage.LIGHT
                    else -> SleepStage.AWAKE
                },
            )
            i = j + 1
        }
        fun minOf(p: Int) = hyp.count { it == p } / 2
        val deep = minOf(OuraEvents.PHASE_DEEP)
        val rem = minOf(OuraEvents.PHASE_REM)
        val light = minOf(OuraEvents.PHASE_LIGHT)
        val inBed = ((be - bs) / 600).toInt()
        val asleep = if (hyp.isEmpty()) inBed else deep + rem + light
        val awake = if (hyp.isEmpty()) 0 else (inBed - asleep).coerceAtLeast(0)

        // --- Battito e respiro (0x6a), con riserva dagli eventi HRV
        val periodEvents = events.filter { it.tag == 0x6a && inWindow(it) }.sortedBy { it.ringDecis }
            .mapNotNull { e -> OuraEvents.decodeSleepPeriodInfo(e.body)?.let { e.ringDecis to it } }
        val periods = periodEvents.map { it.second }
        var hr = periods.map { it.avgHr }.filter { it in 25.0..150.0 }
        val breath = periods.map { it.breath }.filter { it in 4.0..40.0 }
        var hrSeries = DailySummary.bin(
            periodEvents.filter { it.second.avgHr in 25.0..150.0 }.map { Sample(toMs(it.first), it.second.avgHr) }
        )

        // HRV: ogni evento porta N coppie a 5 minuti e viene emesso alla FINE del blocco
        // (verificato sui dati reali: 6 coppie ogni 30 minuti, l'ultimo evento subito dopo
        // il risveglio). Il campione k ha quindi orario ts - (N-1-k)·5 min.
        val rmssd = ArrayList<Int>()
        val hrvHr = ArrayList<Double>()
        val hrvSeries = ArrayList<Sample>()
        val hrvHrSeries = ArrayList<Sample>()
        events.filter { it.tag == 0x5d && it.ringDecis in bs..(be + 18_000) }.sortedBy { it.ringDecis }.forEach { e ->
            OuraEvents.decodeHrv(e.body)?.let { (h, r) ->
                for (k in r.indices) {
                    val t = e.ringDecis - (r.size - 1 - k) * 3_000L
                    if (t < bs || t > be + 3_000) continue
                    if (r[k] in 5..250) { rmssd.add(r[k]); hrvSeries.add(Sample(toMs(t), r[k].toDouble())) }
                    if (h[k] in 25..150) { hrvHr.add(h[k].toDouble()); hrvHrSeries.add(Sample(toMs(t), h[k].toDouble())) }
                }
            }
        }
        if (hr.isEmpty()) { hr = hrvHr; hrSeries = hrvHrSeries }

        // Temperatura della pelle nel sonno (0x75): un valore ogni 30 s circa.
        val temps = ArrayList<Double>()
        events.filter { it.tag == 0x75 && inWindow(it) }.forEach { e ->
            OuraEvents.decodeTemps(e.body)?.forEach { if (it in 25.0..42.0) temps.add(it) }
        }

        // FC più bassa: minimo della media mobile su ~5 minuti (10 campioni da 30 s).
        val w = if (periods.isNotEmpty()) 10 else 1
        val lowest = if (hr.size >= w) (0..hr.size - w).minOf { k -> hr.subList(k, k + w).average() } else hr.minOrNull()

        val spo2 = ArrayList<Int>()
        events.filter { it.tag == 0x6f && inWindow(it) }.forEach { e ->
            OuraEvents.decodeSpo2(e.body)?.forEach { if (it in 70..100) spo2.add(it) }
        }

        val endMs = toMs(be)
        return DailySummary(
            date = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate(),
            sleepStartMs = toMs(bs),
            sleepEndMs = endMs,
            inBedMin = inBed,
            asleepMin = asleep,
            deepMin = deep,
            remMin = rem,
            lightMin = light,
            awakeMin = awake,
            lowestHr = lowest,
            avgSleepHr = if (hr.isEmpty()) null else hr.average(),
            avgHrv = if (rmssd.isEmpty()) null else rmssd.average(),
            restingHr = lowest,
            spo2 = if (spo2.isEmpty()) null else spo2.average(),
            respiratoryRate = if (breath.isEmpty()) null else breath.average(),
            steps = null,
            activeKcal = null,
            hypnogram = spans,
            skinTemp = if (temps.size >= 20) temps.average() else null,
            hrSeries = hrSeries,
            hrvSeries = hrvSeries,
        )
    }
}
