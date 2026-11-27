package it.anello.app.data

import android.content.Context

const val OURA_PACKAGE = "com.ouraring.oura"

/** Da dove arrivano i dati delle schermate Oggi/Sonno/Andamento. */
enum class DataSource { HEALTH_CONNECT, RING }

data class UserSettings(
    val source: DataSource = DataSource.HEALTH_CONNECT,
    val onlyOura: Boolean = true,
    val sleepGoalMin: Int = 8 * 60,
    val stepsGoal: Int = 8000,
    /** Giorni confermati come "nuovo livello normale" dell'HRV, per fonte (ISO yyyy-mm-dd). */
    val rebaselineHc: Set<String> = emptySet(),
    val rebaselineRing: Set<String> = emptySet(),
) {
    fun rebaselineDays(): Set<java.time.LocalDate> =
        (if (source == DataSource.RING) rebaselineRing else rebaselineHc)
            .mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }.toSet()
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = UserSettings(
        source = runCatching { DataSource.valueOf(prefs.getString("source", "HEALTH_CONNECT")!!) }
            .getOrDefault(DataSource.HEALTH_CONNECT),
        onlyOura = prefs.getBoolean("onlyOura", true),
        sleepGoalMin = prefs.getInt("sleepGoalMin", 8 * 60),
        stepsGoal = prefs.getInt("stepsGoal", 8000),
        rebaselineHc = prefs.getStringSet("rebaselineHc", emptySet())!!.toSet(),
        rebaselineRing = prefs.getStringSet("rebaselineRing", emptySet())!!.toSet(),
    )

    fun save(s: UserSettings) {
        prefs.edit()
            .putString("source", s.source.name)
            .putBoolean("onlyOura", s.onlyOura)
            .putInt("sleepGoalMin", s.sleepGoalMin)
            .putInt("stepsGoal", s.stepsGoal)
            .putStringSet("rebaselineHc", s.rebaselineHc)
            .putStringSet("rebaselineRing", s.rebaselineRing)
            .apply()
    }
}
