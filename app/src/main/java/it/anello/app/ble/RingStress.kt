package it.anello.app.ble

import android.content.Context
import it.anello.app.data.DailySummary
import it.anello.app.score.Insights
import it.anello.app.score.Stress
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Stress diurno dagli eventi grezzi dell'anello, con il suo archivio per giorno. */
object RingStress {

    /** Finestre di stress dagli eventi: battiti da svegli (0x80) e movimento (0x47), con l'orario dalle ancore (0x42). */
    fun build(events: List<OuraEvents.Event>, nights: Collection<DailySummary>): List<Stress.Window> {
        val anchors = events.filter { it.tag == 0x42 }
            .mapNotNull { e -> OuraEvents.decodeTimeSyncUnix(e.body)?.let { Pair(e.ringDecis, it) } }
        if (anchors.isEmpty()) return emptyList()
        fun toMs(ds: Long): Long {
            val a = anchors.minBy { kotlin.math.abs(it.first - ds) }
            return a.second * 1000 + (ds - a.first) * 100
        }
        val low = nights.filter { it.hasSleep }.sortedBy { it.date }.takeLast(14).mapNotNull { it.lowestHr }
        if (low.isEmpty()) return emptyList()
        val beats = ArrayList<Pair<Long, Int>>()
        val motion = ArrayList<Pair<Long, Int>>()
        for (e in events) {
            when (e.tag) {
                0x80 -> { val t = toMs(e.ringDecis); OuraEvents.decodeGreenIbi(e.body).forEach { (ibi, q) -> if (q == 1) beats += Pair(t, ibi) } }
                0x47 -> OuraEvents.decodeMotionSeconds(e.body)?.let { motion += Pair(toMs(e.ringDecis), it) }
            }
        }
        val asleep = nights.mapNotNull { n -> n.sleepStartMs?.let { s -> n.sleepEndMs?.let { s..it } } }
        return Stress.windows(beats, motion, Insights.median(low), asleep)
    }

    private fun file(context: Context, address: String) = File(context.filesDir, "stress_${address.replace(":", "")}.json")

    /** Finestre salvate, per giorno locale. */
    fun load(context: Context, address: String): Map<LocalDate, List<Stress.Window>> {
        val f = file(context, address)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associate { k ->
                val a = o.getJSONArray(k)
                LocalDate.parse(k) to (0 until a.length()).map { i ->
                    val w = a.getJSONArray(i)
                    Stress.Window(w.getLong(0), w.getDouble(1), w.getDouble(2), Stress.Zone.valueOf(w.getString(3)))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Ricalcola dai dati grezzi (che coprono gli ultimi giorni) e aggiorna l'archivio. Un giorno
     * già salvato viene sostituito solo se il nuovo calcolo ha almeno altrettante finestre: i dati
     * grezzi più vecchi vengono cancellati e un giorno rifatto a metà sarebbe peggiore.
     */
    @Synchronized
    fun update(context: Context, address: String, events: List<OuraEvents.Event>, nights: Collection<DailySummary>, zone: ZoneId = ZoneId.systemDefault()) {
        val fresh = build(events, nights).groupBy { Instant.ofEpochMilli(it.tMs).atZone(zone).toLocalDate() }
        if (fresh.isEmpty()) return
        val all = load(context, address).toMutableMap()
        for ((date, w) in fresh) if (w.size >= (all[date]?.size ?: 0)) all[date] = w
        val o = JSONObject()
        val cutoff = LocalDate.now(zone).minusDays(120)
        for ((date, ws) in all) {
            if (date < cutoff) continue
            o.put(date.toString(), JSONArray().apply {
                ws.forEach { put(JSONArray().put(it.tMs).put(Math.round(it.hr * 10) / 10.0).put(Math.round(it.motion * 100) / 100.0).put(it.zone.name)) }
            })
        }
        file(context, address).writeText(o.toString())
    }
}
