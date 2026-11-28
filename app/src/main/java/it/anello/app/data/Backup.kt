package it.anello.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup completo in un file zip: tutti i file dell'archivio (giorni, diario, allenamenti, dati
 * grezzi) e le impostazioni, comprese le chiavi degli anelli associati. Il file va tenuto privato.
 */
object Backup {
    private const val MARKER = "anello-backup.txt"
    private val SAFE = Regex("[A-Za-z0-9._-]+")

    private fun prefNames(context: Context): List<String> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .map { it.name.removeSuffix(".xml") }
            .filter { SAFE.matches(it) && !it.startsWith("androidx.") && !it.startsWith("com.google.") }

    /** Scrive il backup; restituisce il numero di voci salvate. */
    fun write(context: Context, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("Anello backup 1\n".toByteArray())
            zip.closeEntry()
            for (f in context.filesDir.listFiles().orEmpty()) {
                if (!f.isFile || f.name.endsWith(".tmp") || !SAFE.matches(f.name)) continue
                zip.putNextEntry(ZipEntry("files/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
            for (name in prefNames(context)) {
                val o = JSONObject()
                for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                    val e = JSONObject()
                    when (v) {
                        is String -> e.put("t", "s").put("v", v)
                        is Int -> e.put("t", "i").put("v", v)
                        is Long -> e.put("t", "l").put("v", v)
                        is Boolean -> e.put("t", "b").put("v", v)
                        is Float -> e.put("t", "f").put("v", v.toDouble())
                        is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                        else -> continue
                    }
                    o.put(k, e)
                }
                zip.putNextEntry(ZipEntry("prefs/$name.json"))
                zip.write(o.toString().toByteArray())
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    /**
     * Ripristina un backup sostituendo i dati presenti. Controlla prima che il file sia un backup
     * di Anello; in caso contrario lancia un'eccezione senza toccare niente.
     */
    fun restore(context: Context, input: InputStream): Int {
        val files = LinkedHashMap<String, ByteArray>()
        val prefs = LinkedHashMap<String, JSONObject>()
        var marker = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                val bytes = zip.readBytes()
                when {
                    name == MARKER -> marker = true
                    name.startsWith("files/") && SAFE.matches(name.removePrefix("files/")) -> files[name.removePrefix("files/")] = bytes
                    name.startsWith("prefs/") && name.endsWith(".json") && SAFE.matches(name.removePrefix("prefs/").removeSuffix(".json")) ->
                        prefs[name.removePrefix("prefs/").removeSuffix(".json")] = JSONObject(String(bytes))
                }
            }
        }
        require(marker) { "Il file non è un backup di Anello." }
        for ((name, bytes) in files) File(context.filesDir, name).writeBytes(bytes)
        for ((name, o) in prefs) {
            val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "ss" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            ed.commit()
        }
        return files.size + prefs.size
    }
}
