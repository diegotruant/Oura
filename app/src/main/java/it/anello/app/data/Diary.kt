package it.anello.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Diario: etichette per giorno. Un giorno è "compilato" quando ha una voce, anche vuota
 * ("niente da segnalare"): solo i giorni compilati entrano nelle analisi, così un giorno
 * dimenticato non viene scambiato per un giorno senza alcol o senza allenamento.
 * Le etichette del giorno D valgono per la notte che segue (quella che finisce in D+1).
 */
object Diary {
    /** Etichette disponibili: chiave salvata → testo mostrato. */
    val TAGS: List<Pair<String, String>> = listOf(
        "alcol" to "Alcol",
        "cena_tardi" to "Cena tardiva",
        "allenamento_intenso" to "Allenamento intenso",
        "stress" to "Stress",
        "caffeina_tardi" to "Caffeina tardi",
        "viaggio" to "Viaggio",
        "malattia" to "Malattia",
    )
    fun label(key: String): String = TAGS.firstOrNull { it.first == key }?.second ?: key
}

class DiaryStore(context: Context) {
    private val file = File(context.filesDir, "diary.json")

    @Synchronized
    fun load(): Map<LocalDate, Set<String>> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associate { k ->
                val a = o.getJSONArray(k)
                LocalDate.parse(k) to (0 until a.length()).map { a.getString(it) }.toSet()
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun set(date: LocalDate, tags: Set<String>?) {
        val all = load().toMutableMap()
        if (tags == null) all.remove(date) else all[date] = tags
        val o = JSONObject()
        all.toSortedMap().forEach { (d, t) -> o.put(d.toString(), JSONArray(t.sorted())) }
        val tmp = File(file.parentFile, "diary.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
