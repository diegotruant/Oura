package it.anello.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import it.anello.app.data.SettingsStore
import it.anello.app.data.Sources
import it.anello.app.score.Coach
import it.anello.app.score.Deep
import it.anello.app.score.Scores
import it.anello.app.training.Training
import it.anello.app.training.TrainingStore
import java.time.LocalDate

/**
 * Notifica del mattino: appena la notte di oggi è disponibile nella fonte attiva, una sola volta
 * al giorno, con Recupero, Sonno, consiglio per l'allenamento e stato di salute.
 */
object MorningNotifier {
    private const val CHANNEL = "mattino"
    private const val PREFS = "notify"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Titolo e testo della notifica per oggi, null se la notte non c'è ancora. */
    fun compose(context: Context, today: LocalDate = LocalDate.now()): Pair<String, String>? {
        val settings = SettingsStore(context).load()
        val days = Sources.store(context, settings.sourceId).load()
        val day = days[today]?.takeIf { it.hasSleep } ?: return null
        val history = days.values.sortedBy { it.date }
        val scores = Scores.compute(day, history, settings)
        val advice = Coach.forDay(history, day, settings, Training.dailyLoads(TrainingStore(context).load()))
        val title = "Recupero ${scores.readiness?.value ?: "–"} · Sonno ${scores.sleep?.value ?: "–"}"
        val lines = ArrayList<String>()
        advice?.let { lines += "Oggi: ${it.plan.title}" }
        Deep.health(history, day)?.let { h -> if (h.out > 0) lines += "${h.out} segni vitali fuori dalla tua norma" }
        Deep.alerts(history, today).firstOrNull()?.let { lines += it.title }
        if (lines.isEmpty()) lines += "%dh %02dm di sonno".format(day.asleepMin / 60, day.asleepMin % 60)
        return Pair(title, lines.joinToString(" · "))
    }

    /** Da chiamare dopo una sincronizzazione: notifica se è attiva, permessa e non già inviata oggi. */
    fun maybeNotify(context: Context) {
        if (!isEnabled(context) || !hasPermission(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        if (prefs.getString("last", null) == today.toString()) return
        val (title, text) = runCatching { compose(context, today) }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "Riepilogo del mattino", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1, n)
            prefs.edit().putString("last", today.toString()).apply()
        } catch (_: SecurityException) {
        }
    }
}
