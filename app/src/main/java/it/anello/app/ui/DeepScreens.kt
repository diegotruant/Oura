package it.anello.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.anello.app.data.DailySummary
import it.anello.app.score.Deep
import it.anello.app.score.Stress
import java.time.LocalDate
import kotlin.math.abs

/** Segnalazioni del giorno (scheda Oggi). Non mostra niente se non c'è niente da dire. */
@Composable
fun AlertsCard(history: List<DailySummary>, date: LocalDate) {
    val alerts = remember(history, date) { Deep.alerts(history, date) }
    if (alerts.isEmpty()) return
    SectionCard("Segnalazioni") {
        alerts.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Text(a.title, color = if (a.warning) AwakeColor else ActivityColor, fontWeight = FontWeight.SemiBold)
            Text(a.text, color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Big(text: String, color: androidx.compose.ui.graphics.Color = TextMain) =
    Text(text, color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Note(text: String) = Text(text, color = TextDim, fontSize = 12.sp)

/** Approfondimenti (scheda Trend): misure che vanno oltre i punteggi giornalieri. */
@Composable
fun DeepSection(history: List<DailySummary>) {
    val date = remember(history) { history.lastOrNull { it.hasSleep }?.date ?: LocalDate.now() }
    val day = remember(history, date) { history.firstOrNull { it.date == date } }

    SectionCard("Stato dell'HRV sulla settimana") {
        val s = remember(history, date) { Deep.hrvState(history, date) }
        if (s == null) {
            Note("Servono almeno ${Deep.MIN_WEEK} notti nell'ultima settimana e ${Deep.MIN_BASE} nelle settimane precedenti.")
        } else {
            val (label, color) = when (s.kind) {
                Deep.HrvKind.STABLE -> "Nella norma" to ReadyColor
                Deep.HrvKind.RECOVERED -> "Sopra la norma" to ReadyColor
                Deep.HrvKind.STRAIN -> "Carico non smaltito" to AwakeColor
                Deep.HrvKind.SATURATION -> "HRV e battito entrambi bassi" to AwakeColor
                Deep.HrvKind.LOW -> "Sotto la norma" to AwakeColor
                Deep.HrvKind.UNSETTLED -> "Instabile" to ActivityColor
            }
            Big(label, color)
            Text(
                "Media di 7 giorni %.0f ms · norma %.0f ms · conta uno scarto oltre ±%.0f%%".format(s.weekMs, s.normMs, s.swcPct),
                color = TextMain, fontSize = 13.sp,
            )
            Text(
                "Oscillazione da un giorno all'altro: %.1f%%".format(s.cv7) +
                    (s.cvTypical?.let { " (di solito %.1f%%)".format(it) } ?: "") + if (s.cvHigh) " — più del solito" else "",
                color = TextMain, fontSize = 13.sp,
            )
            if (s.rhrWeek != null && s.rhrNorm != null) {
                Text("Battito a riposo: %.0f bpm · norma %.0f".format(s.rhrWeek, s.rhrNorm), color = TextMain, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Note(
                "Il valore di una sola notte oscilla molto; la media della settimana e la sua stabilità dicono di più su come " +
                    "stai assorbendo il carico. HRV giù con battito su indica stress o fatica; HRV giù con battito giù, negli sport " +
                    "di endurance, può indicare una fatica profonda. Si basa su ${s.nWeek} notti dell'ultima settimana.",
            )
        }
    }

    SectionCard("Regolarità del sonno") {
        val r = remember(history, date) { Deep.regularity(history, date) }
        if (r == null) {
            Note("Servono almeno ${Deep.MIN_REG_NIGHTS} notti nelle ultime 4 settimane.")
        } else {
            if (r.sri != null) Big("Indice di regolarità %.0f su 100".format(r.sri), if (r.sri >= Deep.SRI_LOW) ReadyColor else ActivityColor)
            else Note("Indice di regolarità: servono almeno 5 coppie di notti consecutive.")
            Text(
                "Punto medio del sonno alle ${Deep.clock(r.midpoint)}, con uno scarto tipico di %.0f minuti.".format(r.midpointSd),
                color = TextMain, fontSize = 13.sp,
            )
            r.socialJetlag?.let { j ->
                Text(
                    "Jet lag sociale: %.0f minuti (%s nei giorni liberi).".format(abs(j), if (j >= 0) "più tardi" else "più presto"),
                    color = if (abs(j) >= Deep.JETLAG_MIN) ActivityColor else TextMain, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Note(
                "L'indice misura quanto spesso, a 24 ore di distanza, sei nello stesso stato (sonno o veglia): 100 = orari identici " +
                    "ogni giorno. Negli studi sulla popolazione la regolarità è legata alla salute almeno quanto la durata. " +
                    "Il jet lag sociale confronta sabato e domenica con gli altri giorni. Calcolato su ${r.nights} notti; la soglia di " +
                    "%.0f è indicativa.".format(Deep.SRI_LOW),
            )
        }
    }

    SectionCard("Forma dell'ultima notte") {
        val shape = remember(history, day) { day?.let { Deep.nightShape(history, it) } }
        val frag = remember(history, day) { day?.let { Deep.fragmentation(history, it) } }
        if (shape == null && frag == null) Note("Servono la curva del battito o le fasi del sonno della notte.")
        shape?.let {
            Text(
                "Battito minimo al %.0f%% della notte".format(it.nadir * 100) + (it.typical?.let { t -> " (di solito al %.0f%%)".format(t * 100) } ?: ""),
                color = if (it.late) ActivityColor else TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Note("Un minimo verso metà notte è il segno di un corpo che si è calmato presto; un minimo vicino al risveglio capita dopo cena tardiva, alcol o allenamento serale.")
        }
        frag?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "%d risvegli · %.1f all'ora".format(it.awakenings, it.perHour) + (it.typicalPerHour?.let { t -> " (di solito %.1f)".format(t) } ?: "") +
                    " · %d minuti sveglio".format(it.wasoMin),
                color = if (it.high) ActivityColor else TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Note("Risvegli di almeno un minuto tra l'addormentamento e il risveglio finale, per ora di sonno.")
        }
    }

    SectionCard("Le tue leve") {
        val l = remember(history, date) { Deep.levers(history, date) }
        if (l == null || (l.sleepHours == null && l.bedtime == null)) {
            Note("Servono almeno ${Deep.MIN_LEVER_NIGHTS} notti con HRV (finora ${l?.n ?: 0} utilizzabili) e orari non sempre uguali.")
        } else {
            @Composable
            fun row(title: String, lever: Deep.Lever?) {
                if (lever == null) return
                Text(title, color = TextMain, fontSize = 13.sp)
                Big("%+.1f%% di HRV".format(lever.pct), if (!lever.reliable) TextDim else if (lever.pct >= 0) ReadyColor else AwakeColor)
                Note("tra %+.1f%% e %+.1f%% · ${lever.n} notti · ".format(lever.low, lever.high) + if (lever.reliable) "effetto distinguibile" else "ancora incerto")
                Spacer(Modifier.height(8.dp))
            }
            row("Ogni ora di sonno in più", l.sleepHours)
            row("Ogni ora di ritardo nell'andare a letto", l.bedtime)
            Note(
                "Quanto cambia la tua HRV della notte, rispetto al tuo livello delle settimane precedenti, quando dormi di più o vai a " +
                    "letto più tardi del tuo solito. È un'associazione sui tuoi dati, non una prova di causa: nelle sere in cui vai a letto " +
                    "tardi spesso succedono anche altre cose.",
            )
        }
    }
}

private fun zoneColor(z: Stress.Zone) = when (z) {
    Stress.Zone.CALM -> ReadyColor
    Stress.Zone.ENGAGED -> SleepColor
    Stress.Zone.TENSE -> AwakeColor
    Stress.Zone.ACTIVE -> ActivityColor
}

private fun hm(min: Int) = if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "${min}m"

/** Stress durante il giorno: striscia oraria a colori e minuti per stato. */
@Composable
fun StressCard(windows: List<Stress.Window>) {
    val sum = remember(windows) { Stress.summary(windows) }
    SectionCard("Stress durante il giorno") {
        val first = windows.first().tMs
        val last = windows.last().tMs + Stress.WINDOW_MS
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val span = (last - first).toFloat().coerceAtLeast(1f)
            windows.forEach { w ->
                val x = (w.tMs - first) / span * size.width
                val wd = Stress.WINDOW_MS / span * size.width
                drawRect(zoneColor(w.zone), topLeft = Offset(x, 0f), size = Size(wd + 0.5f, size.height))
            }
        }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALIAN)
        Note("dalle ${fmt.format(java.util.Date(first))} alle ${fmt.format(java.util.Date(last))}")
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple("Tensione", sum.tenseMin, AwakeColor),
            Triple("Impegno normale", sum.engagedMin, SleepColor),
            Triple("Calma", sum.calmMin, ReadyColor),
            Triple("In movimento", sum.activeMin, ActivityColor),
        ).forEach { (label, min, color) ->
            Row {
                Text(label, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(hm(min), color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Note(
            "Tensione = battito alto mentre sei fermo (almeno il 40% sopra il tuo minimo notturno): la parte in più non è spiegata " +
                "dal movimento. Calma = battito vicino al minimo notturno. Conta solo il tempo in cui l'anello ha misurato " +
                "(${hm(sum.measuredMin)}); le soglie sono indicative.",
        )
    }
}
