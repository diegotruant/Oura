package it.anello.app.dev

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Dispositivi aggiunti (oltre agli anelli Oura): tipo, nome, batteria, ultima sincronizzazione,
 * e lo storico grezzo dei RingConn, che dopo la lettura non esiste più sull'anello.
 */
class DeviceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    data class Device(
        val address: String,
        val kind: String,
        val name: String,
        val battery: Int? = null,
        val lastSync: Long = 0,
        val message: String? = null,
    )

    fun devices(): List<Device> = prefs.all.keys.filter { it.startsWith("dev_") }.mapNotNull { get(it.removePrefix("dev_")) }.sortedBy { it.name }

    fun get(address: String): Device? {
        val o = runCatching { JSONObject(prefs.getString("dev_$address", null) ?: return null) }.getOrNull() ?: return null
        return Device(
            address, o.optString("kind"), o.optString("name", address),
            if (o.has("battery")) o.getInt("battery") else null, o.optLong("lastSync"),
            if (o.has("message")) o.getString("message") else null,
        )
    }

    fun save(d: Device) {
        val o = JSONObject().put("kind", d.kind).put("name", d.name).put("lastSync", d.lastSync)
        d.battery?.let { o.put("battery", it) }
        d.message?.let { o.put("message", it) }
        prefs.edit().putString("dev_${d.address}", o.toString()).apply()
    }

    fun remove(address: String) {
        prefs.edit().remove("dev_$address").apply()
    }

    fun kind(address: String): String? = get(address)?.kind

    // --- storico grezzo RingConn: una riga per record --------------------------------------

    private fun recordFile(address: String) = File(context.filesDir, "ringconn_${address.replace(":", "")}.csv")

    /** Salva i record su disco PRIMA che la pagina venga confermata all'anello. */
    @Synchronized
    fun appendRecords(address: String, records: List<RingConn.Record>) {
        if (records.isEmpty()) return
        recordFile(address).appendText(records.joinToString("") { r ->
            "${r.unix},${r.hr ?: ""},${r.hrv ?: ""},${r.resp ?: ""},${r.spo2 ?: ""},${r.motion}\n"
        })
    }

    /** Record salvati da [sinceUnix] in poi, senza doppioni. */
    @Synchronized
    fun loadRecords(address: String, sinceUnix: Long = 0): List<RingConn.Record> {
        val f = recordFile(address)
        if (!f.exists()) return emptyList()
        val out = HashMap<Long, RingConn.Record>()
        f.forEachLine { line ->
            val p = line.split(',')
            val t = p.getOrNull(0)?.toLongOrNull() ?: return@forEachLine
            if (p.size < 6 || t < sinceUnix) return@forEachLine
            out[t] = RingConn.Record(t, p[1].toIntOrNull(), p[2].toIntOrNull(), p[3].toDoubleOrNull(), p[4].toIntOrNull(), p[5].toIntOrNull() ?: 0)
        }
        return out.values.sortedBy { it.unix }
    }

    companion object {
        const val COLMI = "colmi"
        const val RINGCONN = "ringconn"
    }
}
