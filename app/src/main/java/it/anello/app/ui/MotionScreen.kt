package it.anello.app.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.anello.app.MotionViewModel
import it.anello.app.motion.MotionLabels
import it.anello.app.motion.MotionSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val sessionTime = SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN)

/** Registrazione dell'accelerometro dell'anello per il motore dei passi. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionCard(vm: MotionViewModel, onExport: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf("walk") }
    var seconds by rememberSaveable { mutableStateOf(120) }

    SectionCard("Registra movimento (ricerca sui passi)") {
        Text(
            "Registra l'accelerometro dell'anello (circa 50 volte al secondo) durante un'attività nota. Serve a costruire e " +
                "verificare un contapassi nostro: conta i passi a mano o da un video e inseriscili dopo. Consuma batteria " +
                "dell'anello: usalo per sessioni brevi.",
            color = TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (s.recording) {
            Text("${s.samples} campioni", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            s.status?.let { Text(it, color = TextDim, fontSize = 13.sp) }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = vm::cancel) { Text("Annulla") }
        } else {
            Text("Attività", color = TextMain)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionLabels.ALL.forEach { (key, text, _) ->
                    FilterChip(selected = label == key, onClick = { label = key }, label = { Text(text) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Durata", color = TextMain)
                listOf(60, 120, 300).forEach { d ->
                    FilterChip(selected = seconds == d, onClick = { seconds = d }, label = { Text("${d / 60} min") })
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = { vm.record(label, seconds) }) { Text("Registra") }
            s.status?.let { Spacer(Modifier.height(6.dp)); Text(it, color = TextDim, fontSize = 13.sp) }
        }

        if (s.sessions.isNotEmpty() && !s.recording) {
            Spacer(Modifier.height(12.dp))
            Text("Sessioni (${s.sessions.size})", color = TextMain, fontWeight = FontWeight.SemiBold)
            s.sessions.sortedByDescending { it.id }.take(12).forEach { m -> SessionRow(m, vm) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.train() }) { Text("Addestra classificatore") }
                OutlinedButton(onClick = onExport) { Text("Esporta CSV") }
            }
            Text(
                if (s.hasModel) "Conteggio con il classificatore addestrato sulle tue sessioni."
                else "Conteggio con la regola semplice (cadenza 70–210 passi/min e passi regolari).",
                color = TextDim, fontSize = 12.sp,
            )
            if (s.hasModel) TextButton(onClick = { vm.forgetModel() }) { Text("Elimina il modello") }
            s.trainMessage?.let { Text(it, color = TextDim, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SessionRow(m: MotionSession, vm: MotionViewModel) {
    var input by rememberSaveable(m.id) { mutableStateOf(m.trueSteps?.toString() ?: "") }
    Spacer(Modifier.height(8.dp))
    Text("${sessionTime.format(Date(m.id))} · ${MotionLabels.text(m.label)} · ${m.seconds} s", color = TextMain, fontSize = 13.sp)
    val error = m.trueSteps?.takeIf { it > 0 }?.let { (m.steps - it) * 100.0 / it }
    Text(
        "contati ${m.steps} (${m.candidates} candidati)" +
            (m.trueSteps?.let { t -> " · veri $t" + (error?.let { e -> " · errore %+.0f%%".format(e) } ?: if (m.steps > 0) " · ${m.steps} falsi passi" else " · nessun falso passo") } ?: "") +
            (if (m.lostFrames > 0) " · ${m.lostFrames} pacchetti persi" else "") +
            (if (m.scaleQuality > 0.03) " · scala stimata incerta" else ""),
        color = when {
            error == null -> TextDim
            kotlin.math.abs(error) <= 5 -> ReadyColor
            else -> AwakeColor
        },
        fontSize = 12.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input, onValueChange = { v -> input = v.filter { it.isDigit() }.take(6) },
            label = { Text("Passi veri") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { vm.setTrueSteps(m.id, input.toIntOrNull()) }) { Text("Salva") }
        TextButton(onClick = { vm.delete(m.id) }) { Text("Elimina") }
    }
}
