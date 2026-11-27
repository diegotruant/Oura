package it.anello.app.data

import android.content.Context
import it.anello.app.dev.DeviceStore

/**
 * Identità delle fonti di dati. Ogni fonte ha il suo archivio di notti e la sua norma:
 * - "hc:<pacchetto>": un'app che scrive in Health Connect (app Oura, Fitbit, Garmin, …);
 * - "ring:<indirizzo>": un anello Oura letto direttamente via Bluetooth;
 * - "dev:<indirizzo>": un altro dispositivo letto direttamente via Bluetooth (Colmi, RingConn).
 */
object Sources {
    fun hc(pkg: String) = "hc:$pkg"
    fun ring(address: String) = "ring:$address"
    fun device(address: String) = "dev:$address"
    /** Fonte letta direttamente via Bluetooth (anello Oura o altro dispositivo). */
    fun isRing(id: String) = id.startsWith("ring:") || id.startsWith("dev:")
    fun deviceAddress(id: String): String? = id.removePrefix("dev:").takeIf { id.startsWith("dev:") }
    fun hcPackage(id: String): String? = id.removePrefix("hc:").takeIf { id.startsWith("hc:") }
    fun ringAddress(id: String): String? = id.removePrefix("ring:").takeIf { id.startsWith("ring:") }

    val DEFAULT = hc(OURA_PACKAGE)

    private val KNOWN = mapOf(
        OURA_PACKAGE to "App Oura",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.whoop.android" to "Whoop",
        "fi.polar.polarflow" to "Polar Flow",
        "com.withings.wiscale2" to "Withings",
    )

    /** Nome leggibile della fonte. */
    fun label(context: Context, id: String): String {
        hcPackage(id)?.let { pkg ->
            KNOWN[pkg]?.let { return it }
            return runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
        deviceAddress(id)?.let { a ->
            val name = DeviceStore(context).get(a)?.name ?: "Dispositivo"
            return "$name (${a.takeLast(5)})"
        }
        val addr = ringAddress(id) ?: return id
        val prefs = context.getSharedPreferences("ring_ble", Context.MODE_PRIVATE)
        val name = prefs.getString("name_$addr", null)?.takeIf { it.isNotBlank() } ?: "Anello"
        return "$name (${addr.takeLast(5)})"
    }

    /**
     * File dell'archivio. I file creati prima delle fonti multiple restano dove sono:
     * days.json per l'app Oura e days_ring.json per il primo anello associato.
     */
    fun fileFor(context: Context, id: String): String {
        hcPackage(id)?.let { pkg ->
            return if (pkg == OURA_PACKAGE) "days.json" else "days_hc_${pkg.replace(Regex("[^A-Za-z0-9]"), "_")}.json"
        }
        deviceAddress(id)?.let { return "days_dev_${it.replace(":", "")}.json" }
        val addr = ringAddress(id) ?: return "days_other.json"
        val legacy = context.getSharedPreferences("ring_ble", Context.MODE_PRIVATE).getString("legacy_ring", null)
        return if (addr == legacy) "days_ring.json" else "days_ring_${addr.replace(":", "")}.json"
    }

    fun store(context: Context, id: String) = DailyStore(context, fileFor(context, id))
}
