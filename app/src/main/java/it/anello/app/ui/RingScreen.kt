package it.anello.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.anello.app.RingViewModel

@Composable
fun RingScreen(
    vm: RingViewModel,
    strapVm: it.anello.app.StrapViewModel,
    motionVm: it.anello.app.MotionViewModel,
    onExportMotion: () -> Unit,
    onExportMotionWindows: () -> Unit,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onExport: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showPairDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Collegamento diretto all'anello", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = TextMain)
        Text(
            "Sperimentale. Legge i dati via Bluetooth senza l'app Oura, basandosi sul " +
                "protocollo open_oura. Richiede un anello Ring 3/4/5. Il collegamento non è " +
                "ancora testato: serve il tuo riscontro.",
            color = TextDim, fontSize = 13.sp,
        )

        s.battery?.let { (pct, time) ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Batteria anello", color = TextDim, modifier = Modifier.weight(1f))
                    Text("$pct%", color = when {
                        pct >= 50 -> ReadyColor
                        pct >= 20 -> ActivityColor
                        else -> AwakeColor
                    }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "letta alle " + java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.ITALIAN)
                        .format(java.util.Date(time)) + " · si aggiorna con Leggi info o Sincronizza",
                    color = TextDim, fontSize = 11.sp,
                )
            }
        }

        if (!hasPermissions) {
            SectionCard {
                Text("Servono i permessi Bluetooth.", color = TextMain)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestPermissions) { Text("Concedi permessi") }
            }
            return@Column
        }
        if (!vm.bluetoothOn()) {
            SectionCard { Text("Attiva il Bluetooth per continuare.", color = AwakeColor) }
        }

        if (s.paired.isNotEmpty()) {
            SectionCard("Anelli associati") {
                s.paired.forEach { (addr, name) ->
                    val sel = addr == s.selected
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) SleepColor.copy(alpha = 0.18f) else Color.Transparent)
                            .border(1.dp, if (sel) SleepColor else Color(0xFF2A2F3A), RoundedCornerShape(10.dp))
                            .clickable { vm.select(addr) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = TextMain)
                            Text(addr, color = TextDim, fontSize = 11.sp)
                        }
                        if (sel) Text("selezionato", color = SleepColor, fontSize = 12.sp)
                    }
                }
                Text(
                    "Ogni anello ha la sua chiave, il suo storico e la sua norma. La sincronizzazione automatica li aggiorna tutti.",
                    color = TextDim, fontSize = 11.sp,
                )
            }
        }

        // Scansione
        SectionCard("Anelli vicini") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.scanning) OutlinedButton(onClick = vm::stopScan, enabled = !s.busy) { Text("Ferma scansione") }
                else Button(onClick = vm::startScan, enabled = !s.busy) { Text("Cerca anello") }
                if (s.scanning) CircularProgressIndicator(Modifier.height(24.dp).padding(start = 4.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(8.dp))
            if (s.devices.isEmpty()) {
                Text("Nessun anello trovato. Indossa l'anello o mettilo sul caricatore e riprova.", color = TextDim, fontSize = 13.sp)
            }
            s.devices.forEach { d ->
                val sel = d.address == s.selected
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) SleepColor.copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, if (sel) SleepColor else Color(0xFF2A2F3A), RoundedCornerShape(10.dp))
                        .clickable { vm.select(d.address) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = TextMain)
                        Text(d.address, color = TextDim, fontSize = 11.sp)
                    }
                    Text("${d.rssi} dBm", color = TextDim, fontSize = 11.sp)
                }
            }
        }

        if (s.selected != null) {
            SectionCard("Anello selezionato") {
                Text(s.selected!!, color = TextDim, fontSize = 12.sp)
                s.info?.let { info ->
                    Spacer(Modifier.height(6.dp))
                    info.firmware?.let { Text("Firmware $it", color = TextMain) }
                    info.mac?.let { Text("MAC $it", color = TextDim, fontSize = 12.sp) }
                    info.battery?.let { Text("Batteria $it%", color = TextMain) }
                    info.diagnosis?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Diagnosi: $it", color = if (info.authed || info.pairable) ReadyColor else AwakeColor, fontSize = 13.sp)
                    }
                    info.error?.let { Text("Errore: $it", color = AwakeColor, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::readInfo, enabled = !s.busy) { Text("Leggi info") }
                    Button(onClick = vm::sync, enabled = !s.busy && s.selectedKeyHex != null) { Text("Sincronizza") }
                }
                Spacer(Modifier.height(10.dp))
                if (s.selectedKeyHex == null) {
                    Text("Nessuna chiave per questo anello. Devi associarlo (reset richiesto) oppure importare la chiave.", color = AwakeColor, fontSize = 13.sp)
                } else {
                    Text("Chiave salvata ✓", color = ReadyColor, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showPairDialog = true }, enabled = !s.busy) { Text("Associa (reset richiesto)") }
                if (s.selectedKeyHex != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Dati sull'andatura (per il contapassi)", color = TextMain)
                    Text(
                        "L'anello può registrare tutto il giorno dei dati sul modo di camminare, che Anello scarica alla " +
                            "sincronizzazione. Sono spenti di fabbrica. Non sono ancora passi: servono a costruire il nostro contapassi." +
                            (s.stepDataMode?.let { if (it == 1) " Stato: attivi." else " Stato: spenti." } ?: ""),
                        color = TextDim, fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.setStepData(true) }, enabled = !s.busy) { Text("Attiva") }
                        OutlinedButton(onClick = { vm.setStepData(false) }, enabled = !s.busy) { Text("Disattiva") }
                    }
                }
            }

            SectionCard("Importa chiave (se catturata)") {
                Text("Chiave di 16 byte (32 caratteri esadecimali), se l'hai ricavata dal registro Bluetooth.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it },
                    singleLine = true, label = { Text("chiave hex") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.importKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Salva chiave") }
            }
        }

        if (s.selectedKeyHex != null) {
            SectionCard("Sincronizzazione automatica") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sincronizza da sola", color = TextMain)
                        Text("All'apertura dell'app e circa ogni ora in background", color = TextDim, fontSize = 12.sp)
                    }
                    Switch(checked = s.autoSync, onCheckedChange = vm::setAutoSync)
                }
                s.lastSync?.let { ls ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ultima: " + java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.ITALIAN)
                            .format(java.util.Date(ls.time)) + " · " + ls.message,
                        color = if (ls.ok) TextDim else AwakeColor, fontSize = 12.sp,
                    )
                }
            }
        }

        // Dati catturati
        s.aggregate?.let { a ->
            SectionCard("Dati catturati dall'anello") {
                Text("${a.totalEvents} eventi totali salvati.", color = TextMain)
                Spacer(Modifier.height(8.dp))
                val st = a.stageMinutes
                if (st.values.any { it > 0 }) {
                    Text("Sonno: profondo ${st["profondo"]}m · leggero ${st["leggero"]}m · REM ${st["rem"]}m · sveglio ${st["sveglio"]}m", color = TextDim)
                }
                a.hrvAvg?.let { Text("HRV media ${it.toInt()} ms (${a.hrvSamples} campioni)", color = TextDim) }
                a.hrAvg?.let { Text("Battito medio ${it.toInt()} bpm", color = TextDim) }
                a.tempAvg?.let { Text("Temperatura media %.1f °C".format(it), color = TextDim) }
                a.spo2Avg?.let { Text("SpO2 media ${it.toInt()}%", color = TextDim) }
                Spacer(Modifier.height(10.dp))
                Text("Tipi di evento:", color = TextDim, fontSize = 12.sp)
                a.eventCounts.forEach { (name, n) -> Text("  $name: $n", color = TextDim, fontSize = 11.sp) }
            }
        }

        SectionCard("Dati grezzi") {
            Text("${s.rawCount} eventi nel log grezzo.", color = TextDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = s.rawCount > 0) { Text("Esporta (JSONL)") }
                OutlinedButton(onClick = vm::clearRaw, enabled = s.rawCount > 0) { Text("Cancella") }
            }
        }

        MotionCard(motionVm, onExportMotion, onExportMotionWindows)

        StrapCard(strapVm)

        // Log
        if (s.log.isNotEmpty()) {
            SectionCard("Log") {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.log.joinToString("\n"))) }) {
                    Text("Copia log")
                }
                Spacer(Modifier.height(8.dp))
                if (s.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    s.log.takeLast(120).forEach {
                        Text(it, color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("Associare l'anello?") },
            text = {
                Text(
                    "L'associazione installa una nuova chiave sull'anello. Funziona SOLO su un " +
                        "anello resettato di fabbrica e non collegato ad altre app. Dopo, l'app Oura " +
                        "non potrà più connettersi a questo anello finché non lo riassoci a Oura. " +
                        "Gli eventi non ancora sincronizzati potrebbero andare persi.\n\n" +
                        "Procedi solo con l'anello di scorta.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPairDialog = false; vm.pair() }) { Text("Associa") }
            },
            dismissButton = { TextButton(onClick = { showPairDialog = false }) { Text("Annulla") } },
        )
    }
}
