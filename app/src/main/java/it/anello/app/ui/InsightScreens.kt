package it.anello.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.anello.app.AppViewModel
import it.anello.app.UiState
import it.anello.app.data.DailySummary
import it.anello.app.data.Diary
import it.anello.app.data.Sample
import it.anello.app.score.Apse
import it.anello.app.score.Insights
import it.anello.app.training.Training
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)

// ---------------------------------------------------------------- diario

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiaryCard(date: LocalDate, tags: Set<String>?, onChange: (Set<String>?) -> Unit) {
    SectionCard("Diario del giorno") {
        Text("Segna cosa è successo in questa giornata: vale per la notte che segue.", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Diary.TAGS.forEach { (key, label) ->
                val on = tags?.contains(key) == true
                FilterChip(
                    selected = on,
                    onClick = { onChange(if (on) (tags ?: emptySet()) - key else (tags ?: emptySet()) + key) },
                    label = { Text(label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tags == null) {
                OutlinedButton(onClick = { onChange(emptySet()) }) { Text("Niente da segnalare") }
            } else {
                Text(if (tags.isEmpty()) "Compilato: niente da segnalare ✓" else "Compilato ✓", color = ReadyColor, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onChange(null) }) { Text("Cancella") }
            }
        }
    }
}

// ---------------------------------------------------------------- avviso multi-segnale

@Composable
fun IllnessCard(history: List<DailySummary>, day: DailySummary) {
    val ill = remember(history, day) { Insights.illness(history, day) } ?: return
    if (!ill.alert) return
    SectionCard {
        Text("Più segnali fuori dalla tua norma", color = AwakeColor, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        ill.signals.forEach { s ->
            Row(Modifier.fillMaxWidth()) {
                Text(s.label, color = TextMain, modifier = Modifier.weight(1f))
                Text(s.text, color = if (s.flagged) AwakeColor else TextDim)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${ill.flagged} segnali su ${ill.signals.size} sono oltre la tua variazione abituale nella stessa notte. " +
                "Può essere l'inizio di una malattia o un forte affaticamento. Non è una diagnosi.",
            color = TextDim, fontSize = 12.sp,
        )
    }
}

fun temperatureText(history: List<DailySummary>, day: DailySummary): Pair<String, String> {
    val dev = Insights.temperature(history, day)
    return when {
        dev != null -> "Temperatura vs norma" to "%+.1f °C".format(dev.delta)
        day.skinTemp != null -> "Temperatura pelle" to "%.1f °C".format(day.skinTemp)
        else -> "Temperatura" to "–"
    }
}

// ---------------------------------------------------------------- grafico a linea

@Composable
fun LineChart(samples: List<Sample>, color: Color, startMs: Long, endMs: Long, height: Dp = 110.dp, markMin: Boolean = false) {
    if (samples.size < 2) {
        Text("Serie non disponibile per questa notte.", color = TextDim, fontSize = 12.sp)
        return
    }
    val lo = samples.minOf { it.v }
    val hi = samples.maxOf { it.v }
    val span = (hi - lo).coerceAtLeast(1.0)
    Row {
        Column(Modifier.height(height), verticalArrangement = Arrangement.SpaceBetween) {
            Text("${hi.roundToInt()}", color = TextDim, fontSize = 11.sp)
            Text("${lo.roundToInt()}", color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.weight(1f).height(height)) {
            val total = (endMs - startMs).toFloat().coerceAtLeast(1f)
            fun x(t: Long) = ((t - startMs) / total).coerceIn(0f, 1f) * size.width
            fun y(v: Double) = size.height - ((v - lo) / span).toFloat() * size.height
            val path = Path()
            var prev: Sample? = null
            for (s in samples) {
                val p = prev
                // Buco di oltre 15 minuti: spezza la linea invece di unire i punti.
                if (p == null || s.tMs - p.tMs > 15 * 60_000) path.moveTo(x(s.tMs), y(s.v))
                else path.lineTo(x(s.tMs), y(s.v))
                prev = s
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (markMin) {
                val m = samples.minBy { it.v }
                drawCircle(color, 4.dp.toPx(), Offset(x(m.tMs), y(m.v)))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(startMs), color = TextDim, fontSize = 11.sp)
        Text(clock(endMs), color = TextDim, fontSize = 11.sp)
    }
}

@Composable
fun NightCharts(d: DailySummary) {
    val start = d.sleepStartMs ?: return
    val end = d.sleepEndMs ?: return
    SectionCard("Battito durante la notte") {
        LineChart(d.hrSeries, AwakeColor, start, end, markMin = true)
        if (d.hrSeries.size >= 6) {
            val m = d.hrSeries.minBy { it.v }
            val pos = ((m.tMs - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
            Spacer(Modifier.height(6.dp))
            Text(
                "Minimo ${m.v.roundToInt()} bpm alle ${clock(m.tMs)}. " + when {
                    pos < 0.5 -> "Arriva nella prima metà della notte: il corpo si è calmato presto."
                    pos < 0.75 -> "Arriva a notte inoltrata."
                    else -> "Arriva verso il mattino: capita spesso dopo cena tardiva, alcol o allenamento serale."
                },
                color = TextDim, fontSize = 12.sp,
            )
        }
    }
    SectionCard("HRV durante la notte") {
        LineChart(d.hrvSeries, RemColor, start, end)
    }
}

// ---------------------------------------------------------------- analisi

@Composable
fun AnalysisSection(state: UiState, history: List<DailySummary>) {
    val effects = remember(history, state.diary) { Insights.tagEffects(history, state.diary) }
    SectionCard("Cosa cambia la tua HRV") {
        Text(
            "Confronto tra le notti dopo un giorno con l'etichetta e le notti dopo i giorni di diario senza. " +
                "Giorni di diario compilati: ${state.diary.size}.",
            color = TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (effects.isEmpty()) {
            Text("Compila il diario nella scheda Oggi: dopo qualche settimana qui vedrai cosa sposta la tua HRV.", color = TextDim)
        }
        effects.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Diary.label(e.tag), color = TextMain, modifier = Modifier.weight(1f))
                if (!e.pct.isNaN()) {
                    Text(
                        "%+.0f%%".format(e.pct),
                        color = if (!e.reliable) TextDim else if (e.pct < 0) AwakeColor else ReadyColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                when {
                    e.pct.isNaN() -> "Servono almeno ${Insights.MIN_GROUP} notti con e ${Insights.MIN_GROUP} senza (finora ${e.nWith} con, ${e.nWithout} senza)."
                    e.pctLow.isNaN() -> "${e.nWith} notti con, ${e.nWithout} senza."
                    else -> "tra %+.0f%% e %+.0f%% · ${e.nWith} notti con, ${e.nWithout} senza · ".format(e.pctLow, e.pctHigh) +
                        if (e.reliable) "differenza affidabile" else "ancora incerto"
                },
                color = TextDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "È un'associazione sui tuoi dati, non una prova di causa: se due etichette vanno spesso insieme " +
                "(es. alcol e cena tardiva) l'effetto di una contiene anche quello dell'altra.",
            color = TextDim, fontSize = 11.sp,
        )
    }

    val check = remember(history, state.diary) { Insights.illnessCheck(history, state.diary) }
    SectionCard("Avviso multi-segnale: verifica") {
        Text(
            "Negli ultimi 90 giorni l'avviso (almeno 2 segnali fuori norma tra temperatura, battito a riposo, HRV e " +
                "respiro) è scattato in ${check.alertDays} notti su ${check.evaluatedDays} valutate.",
            color = TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (check.sickDays == 0) "Quando sei malato segna \"Malattia\" nel diario: qui vedrai quante volte l'avviso l'aveva colto."
            else "Notti legate a un giorno segnato \"Malattia\": ${check.sickDays}, di cui ${check.sickCaught} con l'avviso.",
            color = TextDim, fontSize = 13.sp,
        )
    }
}

// ---------------------------------------------------------------- allenamento

@Composable
fun TrainingSection(state: UiState, vm: AppViewModel, history: List<DailySummary>, onImportFit: () -> Unit) {
    val s = state.settings
    var cp by rememberSaveable(s.cpW) { mutableStateOf(if (s.cpW > 0) s.cpW.toString() else "") }
    var wp by rememberSaveable(s.wPrimeJ) { mutableStateOf(if (s.wPrimeJ > 0) "%.1f".format(java.util.Locale.US, s.wPrimeJ / 1000.0) else "") }
    val cpValue = cp.trim().toIntOrNull()
    val wpValue = wp.trim().replace(',', '.').toDoubleOrNull()
    val valid = cpValue != null && cpValue in 50..700 && wpValue != null && wpValue in 2.0..60.0

    SectionCard("I tuoi valori") {
        Text("Potenza critica (CP) e W′ da test recenti: servono per calcolare carico e W′bal.", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cp, onValueChange = { cp = it }, label = { Text("CP (W)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = wp, onValueChange = { wp = it }, label = { Text("W′ (kJ)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.updateSettings(s.copy(cpW = cpValue!!, wPrimeJ = (wpValue!! * 1000).roundToInt())) },
                enabled = valid,
            ) { Text("Salva") }
            Button(onClick = onImportFit, enabled = s.cpW > 0 && s.wPrimeJ > 0) { Text("Importa file FIT") }
        }
        if (!valid && (cp.isNotBlank() || wp.isNotBlank())) {
            Text("CP tra 50 e 700 W, W′ tra 2 e 60 kJ.", color = AwakeColor, fontSize = 12.sp)
        }
        state.importMessage?.let { Spacer(Modifier.height(6.dp)); Text(it, color = TextDim, fontSize = 13.sp) }
    }

    val loads = remember(state.activities) { Training.dailyLoads(state.activities) }
    val today = LocalDate.now()
    val form = remember(loads) { Training.form(loads, today) }

    if (form.isNotEmpty()) {
        val f = form.last()
        SectionCard("Carico di allenamento") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Cronico 42 g", "${f.chronic.roundToInt()}", Modifier.weight(1f))
                MetricTile("Acuto 7 g", "${f.acute.roundToInt()}", Modifier.weight(1f))
                MetricTile("Bilancio", "%+d".format(f.balance.roundToInt()), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            val last28 = (27 downTo 0).map { today.minusDays(it.toLong()) }
            BarChart(last28.map { loads[it]?.toFloat() }, ActivityColor,
                listOf(last28.first(), last28.last()).map { "${it.dayOfMonth}/${it.monthValue}" }, height = 90.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Carico = ore × (potenza normalizzata / CP)² × 100: un'ora a CP vale 100. Bilancio positivo = fresco, " +
                    "negativo = affaticato. I giorni senza file importato contano come riposo.",
                color = TextDim, fontSize = 11.sp,
            )
        }
    }

    ForecastCard(state, history, loads)

    if (state.activities.isNotEmpty()) {
        SectionCard("Allenamenti importati") {
            state.activities.sortedByDescending { it.startUnix }.take(15).forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a.day.dayOfMonth}/${a.day.monthValue} · ${a.seconds / 60} min · carico ${a.loadAu.roundToInt()}", color = TextMain)
                        Text(
                            "NP ${a.normalizedPowerW?.roundToInt() ?: "–"} W · media ${a.meanPowerW.roundToInt()} W · " +
                                "W′bal minimo ${(a.wbalMinFraction * 100).roundToInt()}%" +
                                if (a.wbalMinFraction < 0) " (CP o W′ sottostimati)" else "",
                            color = TextDim, fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { vm.removeActivity(a.id) }) { Text("Rimuovi") }
                }
            }
        }
    }
}

@Composable
private fun ForecastCard(state: UiState, history: List<DailySummary>, loads: Map<LocalDate, Double>) {
    val s = state.settings
    val apse = remember(history, s) { Apse.cached(history, s.sleepGoalMin / 60.0, s.rebaselineDays()) }
    val model = remember(apse, loads) { Insights.loadResponse(apse, history, loads) }
    var planned by rememberSaveable { mutableStateOf(80f) }

    SectionCard("Previsione del Recupero di domani") {
        if (model == null) {
            Text("Importa i tuoi allenamenti: l'app impara come la tua HRV risponde al carico e stima il Recupero del giorno dopo.", color = TextDim)
            return@SectionCard
        }
        if (!model.ready) {
            Text(
                "Sto imparando la tua risposta al carico. Servono almeno ${Insights.MIN_PAIRS} mattine con HRV dopo il primo " +
                    "allenamento importato (finora ${model.n}) e ${Insights.MIN_TRAINING_DAYS} giorni di allenamento (finora ${model.nTraining}).",
                color = TextDim,
            )
            return@SectionCard
        }
        val last = apse.values.lastOrNull { it.sleepPressureScore != null && it.sleepDebtScore != null }
        val sleepPart = last?.let { (it.sleepPressureScore!! + it.sleepDebtScore!!) / 2 } ?: 80.0
        val fc = Insights.forecast(model, planned.toDouble(), sleepPart)
        Text("Carico di oggi: ${planned.roundToInt()}", color = TextMain)
        Slider(value = planned, onValueChange = { planned = it }, valueRange = 0f..300f)
        if (fc != null) {
            Text("Recupero previsto: ${fc.recovery.roundToInt()}", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("probabilmente tra ${fc.low.roundToInt()} e ${fc.high.roundToInt()} · HRV attesa %+.0f%% rispetto alla norma".format(fc.hrvPct), color = TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        val per100 = (kotlin.math.exp(model.slope * 100) - 1) * 100
        Text(
            "Sui tuoi dati (${model.n} mattine): ogni 100 punti di carico spostano l'HRV del mattino dopo di %+.0f%%. ".format(per100) +
                (if (model.slopeReliable) "L'effetto è distinguibile dal caso." else "L'effetto non è ancora distinguibile dal caso: prendi la previsione con cautela.") +
                " Ipotesi: sonno come l'ultima notte e risposta lineare.",
            color = TextDim, fontSize = 11.sp,
        )
    }
}
