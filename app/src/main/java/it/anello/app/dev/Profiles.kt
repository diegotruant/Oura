package it.anello.app.dev

import java.util.UUID
import kotlin.math.pow

/**
 * Profili Bluetooth standard (specifiche pubbliche del Bluetooth SIG) e riconoscimento della
 * famiglia di un dispositivo dai servizi che espone.
 */
object Profiles {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    fun uuid16(short: Int): UUID = UUID.fromString("%08x".format(short) + BASE_SUFFIX)

    /** Numero a 16 bit di un UUID standard, null per gli UUID proprietari. */
    fun short(u: UUID): Int? {
        val s = u.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX)) s.substring(4, 8).toInt(16) else null
    }

    const val SVC_DEVICE_INFO = 0x180a
    const val SVC_HEART_RATE = 0x180d
    const val SVC_BATTERY = 0x180f
    const val SVC_THERMOMETER = 0x1809
    const val SVC_PULSE_OX = 0x1822
    const val SVC_CYCLING_POWER = 0x1818
    const val SVC_CYCLING_SPEED = 0x1816
    const val SVC_RUNNING_SPEED = 0x1814

    const val CHR_BATTERY = 0x2a19
    const val CHR_MANUFACTURER = 0x2a29
    const val CHR_MODEL = 0x2a24
    const val CHR_FIRMWARE = 0x2a26
    const val CHR_HARDWARE = 0x2a27
    const val CHR_HEART_RATE = 0x2a37
    const val CHR_TEMPERATURE = 0x2a1c
    const val CHR_PLX_SPOT = 0x2a5e
    const val CHR_PLX_CONTINUOUS = 0x2a5f
    const val CHR_CYCLING_POWER = 0x2a63

    private val SERVICE_NAMES = mapOf(
        0x1800 to "Accesso generico", 0x1801 to "Attributi generici",
        SVC_DEVICE_INFO to "Informazioni dispositivo", SVC_HEART_RATE to "Battito cardiaco",
        SVC_BATTERY to "Batteria", SVC_THERMOMETER to "Termometro", SVC_PULSE_OX to "Pulsossimetro",
        SVC_CYCLING_POWER to "Potenza ciclismo", SVC_CYCLING_SPEED to "Velocità e cadenza ciclismo",
        SVC_RUNNING_SPEED to "Velocità e cadenza corsa", 0x1805 to "Ora corrente", 0x181c to "Dati utente",
        0x181d to "Bilancia", 0x181b to "Composizione corporea", 0x1810 to "Pressione sanguigna",
        0x1826 to "Fitness machine", 0xfe59 to "Aggiornamento firmware", 0xfee7 to "Servizio del produttore",
    )

    fun serviceName(u: UUID): String =
        short(u)?.let { SERVICE_NAMES[it] ?: "servizio standard 0x%04x".format(it) } ?: "proprietario ${u.toString().take(8)}…"

    enum class Family(val label: String, val supported: Boolean, val note: String) {
        OURA("Anello Oura", false, "Usa la sezione «Collegamento diretto all'anello» qui sopra."),
        COLMI("Anello Colmi (R02 e simili)", true, "Supportato: passi, battito ogni 5 minuti, sonno, batteria."),
        RINGCONN("Anello RingConn", true, "Supportato: battito, HRV, SpO2, respiro, movimento, batteria. Il sonno è stimato."),
        WHOOP("Whoop", false, "Lo storico non è supportato. Attivando «HR broadcast» nell'app Whoop funziona come fascia cardio."),
        POLAR("Polar", false, "Battito e intervalli RR con il profilo standard: usa la sezione «Fascia cardio»."),
        FITBIT("Fitbit", false, "Protocollo cifrato: i dati arrivano solo tramite Health Connect."),
        GARMIN("Garmin", false, "Protocollo proprietario: i dati arrivano tramite Health Connect."),
    }

    private val VENDOR_PREFIX = listOf(
        "98ed0001-a541-11e4-b6a0-0002a5d5c51b" to Family.OURA,
        "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e" to Family.COLMI,
        "8327ad99-2d87-4a22-a8ce-6dd7971c0437" to Family.RINGCONN,
        "61080001-8d6d-82b8-614a-1c8cb0f8dcc6" to Family.WHOOP,
        "fd4b0001-cce1-4033-93ce-002d5875f58a" to Family.WHOOP,
        "fb005c80-02e7-f387-1cad-8acd2d8df0c8" to Family.POLAR,
        "adabfb00-6e7d-4601-bda2-bffaa68956ba" to Family.FITBIT,
        "6a4e2800-667b-11e3-949a-0800200c9a66" to Family.GARMIN,
        "6a4e2401-667b-11e3-949a-0800200c9a66" to Family.GARMIN,
    )

    /** Famiglia riconosciuta dai servizi proprietari, null se sconosciuta. */
    fun family(services: Collection<UUID>): Family? {
        val set = services.map { it.toString().lowercase() }.toSet()
        return VENDOR_PREFIX.firstOrNull { it.first in set }?.second
    }

    // --- Decodifica delle caratteristiche standard --------------------------------------------

    private fun u8(v: ByteArray, i: Int): Int = v[i].toInt() and 0xff

    fun battery(v: ByteArray): Int? = if (v.isNotEmpty() && u8(v, 0) <= 100) u8(v, 0) else null

    fun text(v: ByteArray): String = String(v, Charsets.UTF_8).trim { it <= ' ' }

    /** Numero in formato IEEE 11073 a 32 bit: mantissa a 24 bit con segno, esponente a 8 bit con segno. */
    fun float32(v: ByteArray, off: Int): Double? {
        if (off + 4 > v.size) return null
        var mant = u8(v, off) or (u8(v, off + 1) shl 8) or (u8(v, off + 2) shl 16)
        if (mant == 0x7fffff || mant == 0x800000 || mant == 0x7ffffe || mant == 0x800002) return null
        if (mant and 0x800000 != 0) mant -= 0x1000000
        return mant * 10.0.pow(v[off + 3].toInt())
    }

    /** Numero in formato IEEE 11073 a 16 bit: mantissa a 12 bit con segno, esponente a 4 bit con segno. */
    fun float16(v: ByteArray, off: Int): Double? {
        if (off + 2 > v.size) return null
        val raw = u8(v, off) or (u8(v, off + 1) shl 8)
        var mant = raw and 0x0fff
        if (mant == 0x07ff || mant == 0x0800 || mant == 0x07fe || mant == 0x0802) return null
        if (mant and 0x0800 != 0) mant -= 0x1000
        var exp = raw shr 12
        if (exp and 0x8 != 0) exp -= 16
        return mant * 10.0.pow(exp)
    }

    /** "Temperature Measurement" (0x2A1C): flag, poi temperatura; bit 0 del flag = Fahrenheit. Restituisce °C. */
    fun temperature(v: ByteArray): Double? {
        if (v.size < 5) return null
        val t = float32(v, 1) ?: return null
        return if (u8(v, 0) and 0x01 != 0) (t - 32) * 5 / 9 else t
    }

    /** Pulsossimetro (0x2A5E puntuale, 0x2A5F continuo): flag, SpO2 e polso. */
    fun pulseOx(v: ByteArray): Pair<Double, Double>? {
        val spo2 = float16(v, 1) ?: return null
        val pulse = float16(v, 3) ?: return null
        return Pair(spo2, pulse)
    }

    /** "Cycling Power Measurement" (0x2A63): flag a 16 bit, poi potenza istantanea in watt (16 bit con segno). */
    fun cyclingPower(v: ByteArray): Int? {
        if (v.size < 4) return null
        return (u8(v, 2) or (u8(v, 3) shl 8)).toShort().toInt()
    }
}
