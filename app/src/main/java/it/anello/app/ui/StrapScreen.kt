package it.anello.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.anello.app.StrapViewModel
import it.anello.app.data.DailySummary
import it.anello.app.data.StrapMeasurement
import it.anello.app.score.Hrv
import it.anello.app.score.Insights
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private val dayTime = SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN)

/** Misura dell'HRV con una fascia cardio Bluetooth, come riferimento per l'anello. */
@Composable
fun StrapCard(vm: StrapViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    SectionCard("Fascia cardio (riferimento)") {
        Text(
            "Una fascia toracica misura i battiti quasi come un elettrocardiogramma. Una misura di 3 minuti al risveglio, " +
                "da fermo, serve a verificare l'HRV dell'anello sui tuoi dati. Funziona con le fasce che usano il profilo " +
                "Bluetooth standard e inviano gli intervalli RR (es. Polar H9/H10, Garmin HRM, Wahoo Tickr).",
            color = TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (s.address != null) Text("Fascia: ${s.name ?: s.address}", color = TextMain)

        if (s.measuring) {
            Spacer(Modifier.height(6.dp))
            Text("%d:%02d".format(s.secondsLeft / 60, s.secondsLeft % 60), color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("${s.liveHr?.let { "$it bpm · " } ?: ""}${s.beats} battiti raccolti", color = TextDim, fontSize = 13.sp)
            s.status?.let { Text(it, color = TextDim, fontSize = 13.sp) }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = vm::cancel) { Text("Annulla") }
        } else {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.scanning) OutlinedButton(onClick = vm::stopScan) { Text("Ferma ricerca") }
                else OutlinedButton(onClick = vm::startScan) { Text(if (s.address == null) "Cerca fascia" else "Cambia fascia") }
                Button(onClick = vm::measure, enabled = s.address != null) { Text("Misura 3 minuti") }
            }
            s.devices.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { vm.select(d) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = TextMain)
                        Text(d.address, color = TextDim, fontSize = 11.sp)
                    }
                    Text("${d.rssi} dBm", color = TextDim, fontSize = 11.sp)
                }
            }
            if (s.scanning && s.devices.isEmpty()) Text("Indossa la fascia con gli elettrodi inumiditi: compare quando è attiva.", color = TextDim, fontSize = 12.sp)
            s.status?.let { Spacer(Modifier.height(6.dp)); Text(it, color = TextDim, fontSize = 13.sp) }
        }

        s.last?.let { m ->
            Spacer(Modifier.height(8.dp))
            Text("RMSSD ${m.rmssd.roundToInt()} ms · ${m.meanHr.roundToInt()} bpm", color = ReadyColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("qualità ${(m.coverage * 100).roundToInt()}% delle coppie di battiti", color = TextDim, fontSize = 12.sp)
        }
        if (s.history.isNotEmpty() && !s.measuring) {
            Spacer(Modifier.height(8.dp))
            Text("Ultime misure", color = TextDim, fontSize = 12.sp)
            s.history.takeLast(5).reversed().forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${dayTime.format(Date(m.timeMs))} · ${m.rmssd.roundToInt()} ms · ${m.meanHr.roundToInt()} bpm",
                        color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.remove(m) }) { Text("Rimuovi") }
                }
            }
        }
    }
}

data class StrapComparison(val n: Int, val r: Double?, val nightOverStrap: Double)

/**
 * Confronto tra l'HRV della notte (anello) e la misura con la fascia la mattina dopo. Sono due
 * misure diverse (media della notte contro 3 minuti da svegli), quindi i valori non devono
 * coincidere: conta se si muovono insieme (correlazione).
 */
fun strapComparison(history: List<DailySummary>, strap: List<StrapMeasurement>): StrapComparison? {
    val morning = strap.groupBy { it.date }.mapValues { (_, l) -> l.minBy { it.timeMs } }
    val pairs = history.mapNotNull { d ->
        val night = d.avgHrv?.takeIf { it > 0 } ?: return@mapNotNull null
        morning[d.date]?.let { ln(night) to ln(it.rmssd) }
    }
    if (pairs.isEmpty()) return null
    return StrapComparison(
        pairs.size,
        if (pairs.size >= 7) Hrv.pearson(pairs.map { it.first }, pairs.map { it.second }) else null,
        exp(Insights.median(pairs.map { it.first - it.second })),
    )
}

@Composable
fun StrapComparisonCard(history: List<DailySummary>, strap: List<StrapMeasurement>) {
    SectionCard("Anello e fascia cardio") {
        val c = strapComparison(history, strap)
        when {
            c == null -> Text("Fai qualche misura al risveglio con la fascia (scheda Anello): qui vedrai se l'HRV dell'anello si muove insieme a quella della fascia.", color = TextDim)
            c.r == null -> Text("${c.n} mattine con entrambe le misure: ne servono 7 per il confronto.", color = TextDim)
            else -> {
                Text("Correlazione %.2f su ${c.n} mattine".format(c.r), color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        c.r >= 0.7 -> "L'HRV dell'anello segue bene quella della fascia: i giorni alti e bassi coincidono."
                        c.r >= 0.4 -> "Accordo discreto: l'andamento è simile ma con differenze."
                        else -> "Accordo debole: controlla il contatto della fascia e che la misura sia fatta sempre nello stesso modo."
                    },
                    color = TextDim, fontSize = 13.sp,
                )
                Text("In media la notte dell'anello vale %.2f volte la misura del mattino.".format(c.nightOverStrap), color = TextDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Sono due misure diverse (media della notte contro 3 minuti da svegli): i valori non devono coincidere, " +
                "conta che si muovano insieme. Per una verifica battito per battito serve indossare la fascia durante la notte.",
            color = TextDim, fontSize = 11.sp,
        )
    }
}
