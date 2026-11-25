package it.anello.app.data

import android.content.Context

const val OURA_PACKAGE = "com.ouraring.oura"

data class UserSettings(
    val onlyOura: Boolean = true,
    val sleepGoalMin: Int = 8 * 60,
    val stepsGoal: Int = 8000,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = UserSettings(
        onlyOura = prefs.getBoolean("onlyOura", true),
        sleepGoalMin = prefs.getInt("sleepGoalMin", 8 * 60),
        stepsGoal = prefs.getInt("stepsGoal", 8000),
    )

    fun save(s: UserSettings) {
        prefs.edit()
            .putBoolean("onlyOura", s.onlyOura)
            .putInt("sleepGoalMin", s.sleepGoalMin)
            .putInt("stepsGoal", s.stepsGoal)
            .apply()
    }
}
