package it.anello.app.data

import android.content.Context
import java.time.LocalDate

const val OURA_PACKAGE = "com.ouraring.oura"

/** Tipo della fonte attiva. */
enum class DataSource { HEALTH_CONNECT, RING }

data class UserSettings(
    /** Fonte attiva per Oggi/Sonno/Trend: vedi [Sources]. */
    val sourceId: String = Sources.DEFAULT,
    /** App di Health Connect seguite come fonti (pacchetti). */
    val hcPackages: Set<String> = setOf(OURA_PACKAGE),
    /** Fonte da cui riempire, dopo la calibrazione, le notti mancanti della fonte attiva. */
    val fillFrom: String? = null,
    val sleepGoalMin: Int = 8 * 60,
    val stepsGoal: Int = 8000,
    /** Giorni confermati come "nuovo livello normale" dell'HRV: voci "fonte|yyyy-mm-dd". */
    val rebaseline: Set<String> = emptySet(),
    /** Potenza critica (W) e W′ (J) per gli allenamenti; 0 = non impostati. */
    val cpW: Int = 0,
    val wPrimeJ: Int = 0,
    /** Dati fisici per le calorie; 0 o vuoto = non impostati. [sex]: "M", "F" o "". */
    val weightKg: Double = 0.0,
    val heightCm: Int = 0,
    val birthYear: Int = 0,
    val sex: String = "",
) {
    val source: DataSource get() = if (Sources.isRing(sourceId)) DataSource.RING else DataSource.HEALTH_CONNECT

    fun rebaselineDays(): Set<LocalDate> =
        rebaseline.filter { it.startsWith("$sourceId|") }
            .mapNotNull { runCatching { LocalDate.parse(it.substringAfterLast('|')) }.getOrNull() }.toSet()
}

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): UserSettings {
        // Migrazione dalle versioni con due sole fonti fisse.
        val legacyRing = context.getSharedPreferences("ring_ble", Context.MODE_PRIVATE).getString("last_addr", null)
        val legacyRingId = legacyRing?.let { Sources.ring(it) }
        val sourceId = prefs.getString("sourceId", null)
            ?: if (prefs.getString("source", "HEALTH_CONNECT") == "RING" && legacyRingId != null) legacyRingId else Sources.DEFAULT
        val rebaseline = prefs.getStringSet("rebaseline", null)?.toSet()
            ?: (prefs.getStringSet("rebaselineHc", emptySet())!!.map { "${Sources.DEFAULT}|$it" } +
                (legacyRingId?.let { id -> prefs.getStringSet("rebaselineRing", emptySet())!!.map { "$id|$it" } } ?: emptyList())).toSet()
        return UserSettings(
            sourceId = sourceId,
            hcPackages = prefs.getStringSet("hcPackages", setOf(OURA_PACKAGE))!!.toSet(),
            fillFrom = prefs.getString("fillFrom", null),
            sleepGoalMin = prefs.getInt("sleepGoalMin", 8 * 60),
            stepsGoal = prefs.getInt("stepsGoal", 8000),
            rebaseline = rebaseline,
            cpW = prefs.getInt("cpW", 0),
            wPrimeJ = prefs.getInt("wPrimeJ", 0),
            weightKg = prefs.getFloat("weightKg", 0f).toDouble(),
            heightCm = prefs.getInt("heightCm", 0),
            birthYear = prefs.getInt("birthYear", 0),
            sex = prefs.getString("sex", "") ?: "",
        )
    }

    fun save(s: UserSettings) {
        prefs.edit()
            .putString("sourceId", s.sourceId)
            .putStringSet("hcPackages", s.hcPackages)
            .putString("fillFrom", s.fillFrom)
            .putInt("sleepGoalMin", s.sleepGoalMin)
            .putInt("stepsGoal", s.stepsGoal)
            .putStringSet("rebaseline", s.rebaseline)
            .putInt("cpW", s.cpW)
            .putInt("wPrimeJ", s.wPrimeJ)
            .putFloat("weightKg", s.weightKg.toFloat())
            .putInt("heightCm", s.heightCm)
            .putInt("birthYear", s.birthYear)
            .putString("sex", s.sex)
            .apply()
    }
}
