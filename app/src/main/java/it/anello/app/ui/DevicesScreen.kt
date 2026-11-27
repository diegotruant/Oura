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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.anello.app.DevicesViewModel
import it.anello.app.dev.DeviceStore
import it.anello.app.dev.Profiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val syncTime = SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN)

/** Altri dispositivi Bluetooth: esplorazione dei profili standard e anelli Colmi e RingConn. */
@Composable
fun DevicesCard(vm: DevicesViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var history by remember { mutableStateOf(false) }

    SectionCard("Altri dispositivi") {
        Text(
            "Sperimentale e mai provato su un dispositivo vero: scritto dai protocolli pubblicati da altri progetti. " +
                "«Esplora» si collega a qualunque dispositivo Bluetooth e legge solo i profili standard (batteria, battito, " +
                "temperatura, potenza), senza modificare niente. Gli anelli Colmi (R02 e simili) e RingConn si possono " +
                "aggiungere come fonte.",
            color = TextDim, fontSize = 12.sp,
        )

        s.devices.forEach { d ->
            Spacer(Modifier.height(10.dp))
            Text(d.name, color = TextMain, fontWeight = FontWeight.SemiBold)
            Text(
                (if (d.kind == DeviceStore.COLMI) "Colmi" else "RingConn") + " · " + d.address +
                    (d.battery?.let { " · batteria $it%" } ?: ""),
                color = TextDim, fontSize = 11.sp,
            )
            if (d.lastSync > 0) Text("Ultima sincronizzazione: ${syncTime.format(Date(d.lastSync))}", color = TextDim, fontSize = 11.sp)
            d.message?.let { Text(it, color = TextDim, fontSize = 12.sp) }
            if (d.kind == DeviceStore.RINGCONN) HistorySwitch(history) { history = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.sync(d.address, history || d.kind == DeviceStore.COLMI) }, enabled = !s.busy) { Text("Sincronizza") }
                TextButton(onClick = { vm.remove(d.address) }, enabled = !s.busy) { Text("Rimuovi") }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (s.scanning) OutlinedButton(onClick = vm::stopScan) { Text("Ferma ricerca") }
        else OutlinedButton(onClick = vm::startScan, enabled = !s.busy) { Text("Cerca dispositivi") }

        s.found.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !s.busy) { vm.explore(f) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = TextMain)
                    Text(f.address, color = TextDim, fontSize = 11.sp)
                }
                Text("${f.rssi} dBm · Esplora", color = SleepColor, fontSize = 12.sp)
            }
        }
        if (s.scanning && s.found.isEmpty()) Text("Ricerca in corso… avvicina il dispositivo.", color = TextDim, fontSize = 12.sp)
        if (s.busy) { Spacer(Modifier.height(6.dp)); Text("Collegamento in corso…", color = ActivityColor, fontSize = 13.sp) }

        s.explored?.let { e ->
            Spacer(Modifier.height(10.dp))
            Text(e.name, color = TextMain, fontWeight = FontWeight.SemiBold)
            if (e.error != null) {
                Text("Non riuscito: ${e.error}", color = AwakeColor, fontSize = 13.sp)
            } else {
                val fam = e.family
                Text(fam?.label ?: "Dispositivo non riconosciuto", color = if (fam?.supported == true) ReadyColor else TextMain, fontSize = 14.sp)
                Text(
                    fam?.note ?: "Si possono usare solo i dati dei profili standard elencati qui sotto; il resto passa da Health Connect, se l'app del produttore lo scrive lì.",
                    color = TextDim, fontSize = 12.sp,
                )
                e.lines.forEach { Text(it, color = TextDim, fontSize = 12.sp) }
                if (fam?.supported == true) {
                    Spacer(Modifier.height(6.dp))
                    if (fam == Profiles.Family.RINGCONN) HistorySwitch(history) { history = it }
                    if (fam == Profiles.Family.COLMI) Text(
                        "Aggiungendolo, l'orologio dell'anello viene impostato (in UTC) e, se è spenta, viene attivata la misura del battito ogni 5 minuti.",
                        color = TextDim, fontSize = 11.sp,
                    )
                    Button(onClick = { vm.add(history || fam == Profiles.Family.COLMI) }, enabled = !s.busy) { Text("Aggiungi e sincronizza") }
                }
            }
        }

        if (s.log.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            s.log.takeLast(12).forEach { Text(it, color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun HistorySwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Scarica lo storico", color = TextMain, fontSize = 13.sp)
            Text(
                "L'anello cancella i dati appena letti: quello che scarichi qui non arriverà più all'app RingConn. " +
                    "Spento, si leggono solo batteria, passi e temperatura.",
                color = TextDim, fontSize = 11.sp,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
