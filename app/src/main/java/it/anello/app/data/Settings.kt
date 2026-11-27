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
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = UserSettings(
        source = runCatching { DataSource.valueOf(prefs.getString("source", "HEALTH_CONNECT")!!) }
            .getOrDefault(DataSource.HEALTH_CONNECT),
        onlyOura = prefs.getBoolean("onlyOura", true),
        sleepGoalMin = prefs.getInt("sleepGoalMin", 8 * 60),
        stepsGoal = prefs.getInt("stepsGoal", 8000),
    )

    fun save(s: UserSettings) {
        prefs.edit()
            .putString("source", s.source.name)
            .putBoolean("onlyOura", s.onlyOura)
            .putInt("sleepGoalMin", s.sleepGoalMin)
            .putInt("stepsGoal", s.stepsGoal)
            .apply()
    }
}
