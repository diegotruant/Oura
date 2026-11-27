package it.anello.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.anello.app.AppViewModel
import it.anello.app.UiState
import it.anello.app.data.DailySummary
import it.anello.app.data.DataSource
import it.anello.app.data.OURA_PACKAGE
import it.anello.app.data.SleepStage
import it.anello.app.score.DayScores
import it.anello.app.score.Score
import it.anello.app.score.Scores
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun fmtTime(ms: Long?) =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt) } ?: "–"

private fun scoresFor(state: UiState, date: LocalDate): DayScores? {
    val day = state.days[date] ?: return null
    return Scores.compute(day, state.days.values.sortedBy { it.date }, state.settings)
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

// ---------------------------------------------------------------- OGGI

@Composable
fun TodayScreen(state: UiState, date: LocalDate, onDate: (LocalDate) -> Unit, onSync: () -> Unit) {
    val day = state.days[date]
    val scores = scoresFor(state, date)
    var detail by rememberSaveable { mutableIntStateOf(1) } // 0 sonno, 1 recupero, 2 attività

    ScreenColumn {
        DateHeader(date, onDate)
        if (state.syncing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Sincronizzazione…", color = TextDim)
            }
        }
        state.lastError?.let { Text("Errore: $it", color = AwakeColor) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ScoreRing("Recupero", scores?.readiness?.value, ReadyColor)
            ScoreRing("Sonno", scores?.sleep?.value, SleepColor)
            ScoreRing("Attività", scores?.activity?.value, ActivityColor)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Recupero", "Sonno", "Attività").forEachIndexed { i, l ->
                val idx = listOf(1, 0, 2)[i]
                FilterChip(selected = detail == idx, onClick = { detail = idx }, label = { Text(l) })
            }
        }

        val (score, color) = when (detail) {
            0 -> scores?.sleep to SleepColor
            2 -> scores?.activity to ActivityColor
            else -> scores?.readiness to ReadyColor
        }
        SectionCard("Cosa ha contribuito") {
            if (score == null) Text("Nessun dato per questo giorno.", color = TextDim)
            else ContributorRows(score.contributors, color)
        }

        if (day != null) KeyMetrics(day)

        if (day == null && !state.syncing) {
            SectionCard {
                Text("Nessun dato. Apri l'app Oura per sincronizzare l'anello, poi torna qui.", color = TextDim)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSync) { Text("Sincronizza ora") }
            }
        } else {
            TextButton(onClick = onSync, enabled = !state.syncing) { Text("Aggiorna dati") }
        }
    }
}

@Composable
private fun KeyMetrics(d: DailySummary) {
    val items = listOf(
        "FC a riposo" to (d.restingHr?.let { "${it.roundToInt()} bpm" } ?: "–"),
        "HRV media" to (d.avgHrv?.let { "${it.roundToInt()} ms" } ?: "–"),
        "Sonno" to (if (d.hasSleep) Scores.fmtMin(d.asleepMin) else "–"),
        "Passi" to (d.steps?.let { "%,d".format(it) } ?: "–"),
        "SpO2" to (d.spo2?.let { "${it.roundToInt()}%" } ?: "–"),
        "Respiro" to (d.respiratoryRate?.let { "%.1f /min".format(it) } ?: "–"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (l, v) -> MetricTile(l, v, Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------- SONNO

@Composable
fun SleepScreen(state: UiState, date: LocalDate, onDate: (LocalDate) -> Unit) {
    val d = state.days[date]
    val score: Score? = scoresFor(state, date)?.sleep
    ScreenColumn {
        DateHeader(date, onDate)
        if (d == null || !d.hasSleep) {
            SectionCard { Text("Nessuna notte registrata per questo giorno.", color = TextDim) }
            return@ScreenColumn
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ScoreRing("Punteggio sonno", score?.value, SleepColor, 110.dp)
            Column {
                Text(Scores.fmtMin(d.asleepMin), style = MaterialTheme.typography.headlineMedium, color = TextMain)
                Text("dormito su ${Scores.fmtMin(d.inBedMin)} a letto", color = TextDim)
                Text("${fmtTime(d.sleepStartMs)} → ${fmtTime(d.sleepEndMs)}", color = TextDim)
            }
        }
        SectionCard("Fasi del sonno") {
            Hypnogram(d.hypnogram)
            Spacer(Modifier.height(12.dp))
            listOf(
                Triple("Profondo", d.deepMin, SleepStage.DEEP),
                Triple("REM", d.remMin, SleepStage.REM),
                Triple("Leggero", d.lightMin, SleepStage.LIGHT),
                Triple("Sveglio", d.awakeMin, SleepStage.AWAKE),
            ).forEach { (label, min, st) ->
                StageRow(label, min, d.inBedMin, stageColor(st))
            }
        }
        SectionCard("Durante la notte") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("FC più bassa", d.lowestHr?.let { "${it.roundToInt()} bpm" } ?: "–", Modifier.weight(1f))
                MetricTile("FC media", d.avgSleepHr?.let { "${it.roundToInt()} bpm" } ?: "–", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("HRV", d.avgHrv?.let { "${it.roundToInt()} ms" } ?: "–", Modifier.weight(1f))
                MetricTile("SpO2", d.spo2?.let { "${it.roundToInt()}%" } ?: "–", Modifier.weight(1f))
            }
        }
        score?.let { SectionCard("Dettaglio punteggio") { ContributorRows(it.contributors, SleepColor) } }
    }
}

@Composable
private fun StageRow(label: String, min: Int, total: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextMain, modifier = Modifier.weight(1f))
        Text("${Scores.fmtMin(min)}  ·  ${if (total > 0) min * 100 / total else 0}%", color = TextDim)
    }
}

// ---------------------------------------------------------------- ANDAMENTO

@Composable
fun TrendsScreen(state: UiState) {
    var range by rememberSaveable { mutableIntStateOf(14) }
    val today = LocalDate.now()
    val dates = (range - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val history = remember(state.days) { state.days.values.sortedBy { it.date } }
    val scores = remember(state.days, state.settings, range) {
        dates.map { d -> state.days[d]?.let { Scores.compute(it, history, state.settings) } }
    }
    val days = dates.map { state.days[it] }
    val labels = listOf(dates.first(), dates[dates.size / 2], dates.last())
        .map { "${it.dayOfMonth}/${it.monthValue}" }

    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30, 90).forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text("$r g") })
            }
        }
        TrendCard("Recupero", scores.map { it?.readiness?.value?.toFloat() }, ReadyColor, labels) { "${it.roundToInt()}" }
        TrendCard("Punteggio sonno", scores.map { it?.sleep?.value?.toFloat() }, SleepColor, labels) { "${it.roundToInt()}" }
        TrendCard("Ore di sonno", days.map { d -> d?.takeIf { it.hasSleep }?.asleepMin?.div(60f) }, SleepColor, labels) { "%.1f h".format(it) }
        TrendCard("HRV notturna", days.map { it?.avgHrv?.toFloat() }, RemColor, labels) { "${it.roundToInt()} ms" }
        TrendCard("FC a riposo", days.map { it?.restingHr?.toFloat() }, AwakeColor, labels) { "${it.roundToInt()} bpm" }
        TrendCard("Passi", days.map { it?.steps?.toFloat() }, ActivityColor, labels) { "%,d".format(it.roundToInt()) }
    }
}

@Composable
private fun TrendCard(title: String, values: List<Float?>, color: Color, labels: List<String>, fmt: (Float) -> String) {
    val present = values.filterNotNull()
    SectionCard(title) {
        if (present.isEmpty()) {
            Text("Nessun dato nel periodo.", color = TextDim)
        } else {
            Row {
                Text("Media ${fmt(present.average().toFloat())}", color = TextMain, modifier = Modifier.weight(1f))
                values.lastOrNull()?.let { Text("Oggi ${fmt(it)}", color = TextDim) }
            }
            Spacer(Modifier.height(10.dp))
            BarChart(values, color, labels)
        }
    }
}

// ---------------------------------------------------------------- IMPOSTAZIONI

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel, onExportCsv: () -> Unit) {
    val s = state.settings
    var sleepGoal by remember(s.sleepGoalMin) { mutableStateOf(s.sleepGoalMin / 60f) }
    var stepsGoal by remember(s.stepsGoal) { mutableStateOf(s.stepsGoal.toFloat()) }

    ScreenColumn {
        SectionCard("Fonte dei dati") {
            Text("Da dove prendono i dati Oggi, Sonno e Andamento", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.source == DataSource.HEALTH_CONNECT,
                    onClick = { vm.updateSettings(s.copy(source = DataSource.HEALTH_CONNECT)) },
                    label = { Text("App Oura") },
                )
                FilterChip(
                    selected = s.source == DataSource.RING,
                    onClick = { vm.updateSettings(s.copy(source = DataSource.RING)) },
                    label = { Text("Anello diretto") },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.source == DataSource.RING)
                    "Notti lette via Bluetooth dall'anello associato nella scheda Anello. Passi e attività non sono ancora disponibili da questa fonte."
                else
                    "Dati scritti dall'app Oura in Health Connect.",
                color = TextDim, fontSize = 12.sp,
            )
        }

        if (s.source == DataSource.HEALTH_CONNECT) SectionCard("Filtro Health Connect") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo dati dell'anello Oura", color = TextMain)
                    Text("Ignora telefono, smartwatch e altre app", color = TextDim)
                }
                Switch(checked = s.onlyOura, onCheckedChange = {
                    vm.updateSettings(s.copy(onlyOura = it)); vm.fullResync()
                })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = vm::loadSources) { Text("Verifica quali app scrivono il sonno") }
            state.sources?.let { src ->
                Spacer(Modifier.height(8.dp))
                if (src.isEmpty()) Text("Nessuna notte trovata negli ultimi 30 giorni.", color = AwakeColor)
                src.forEach { (pkg, n) ->
                    val name = if (pkg == OURA_PACKAGE) "Oura ✓" else pkg
                    Text("$name: $n notti", color = TextDim)
                }
            }
        }

        SectionCard("Obiettivi") {
            Text("Sonno: ${"%.1f".format(sleepGoal)} ore", color = TextMain)
            Slider(
                value = sleepGoal, onValueChange = { sleepGoal = it }, valueRange = 6f..10f, steps = 7,
                onValueChangeFinished = { vm.updateSettings(s.copy(sleepGoalMin = (sleepGoal * 60).roundToInt())) },
            )
            Text("Passi: %,d".format(stepsGoal.roundToInt()), color = TextMain)
            Slider(
                value = stepsGoal, onValueChange = { stepsGoal = it }, valueRange = 4000f..15000f, steps = 21,
                onValueChangeFinished = { vm.updateSettings(s.copy(stepsGoal = stepsGoal.roundToInt())) },
            )
        }

        SectionCard("I tuoi dati") {
            Text("${state.days.size} giorni salvati sul telefono.", color = TextDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportCsv) { Text("Esporta CSV") }
                OutlinedButton(onClick = vm::fullResync, enabled = !state.syncing) { Text("Riscarica 30 giorni") }
            }
        }

        SectionCard("Come sono calcolati i punteggi") {
            Text(
                "Sonno: durata rispetto all'obiettivo, efficienza (tempo dormito/tempo a letto), " +
                    "percentuale di sonno profondo e REM, tempo sveglio.\n\n" +
                    "Recupero: HRV e FC a riposo confrontate con la tua mediana degli ultimi 14 giorni, " +
                    "qualità del sonno della notte e debito di sonno degli ultimi giorni.\n\n" +
                    "Attività: passi e calorie attive rispetto all'obiettivo.\n\n" +
                    "Sono algoritmi di questa app, non quelli di Oura: i numeri non coincideranno, " +
                    "ma l'andamento nel tempo è confrontabile. Servono circa 3-5 notti per la tua media personale.",
                color = TextDim,
            )
        }
    }
}
