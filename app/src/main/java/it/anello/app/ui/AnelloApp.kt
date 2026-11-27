package it.anello.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.anello.app.AppViewModel
import it.anello.app.HcStatus
import it.anello.app.data.DataSource
import java.time.LocalDate

val Bg = Color(0xFF0E1116)
val Card = Color(0xFF181C24)
val TextMain = Color(0xFFE8EDF5)
val TextDim = Color(0xFF8B95A7)
val SleepColor = Color(0xFF7FB3FF)
val ReadyColor = Color(0xFF7FE0C3)
val ActivityColor = Color(0xFFFFB37F)
val DeepColor = Color(0xFF3D5AFE)
val RemColor = Color(0xFF9C7BFF)
val LightColor = Color(0xFF7FB3FF)
val AwakeColor = Color(0xFFFF8A80)

private val colors = darkColorScheme(
    background = Bg,
    surface = Bg,
    surfaceContainer = Card,
    primary = SleepColor,
    onBackground = TextMain,
    onSurface = TextMain,
)

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Oggi", Icons.Filled.Home),
    SLEEP("Sonno", Icons.Filled.Favorite),
    TRENDS("Trend", Icons.Filled.DateRange),
    RING("Anello", Icons.Filled.Share),
    SETTINGS("Opzioni", Icons.Filled.Settings),
}

@Composable
fun AnelloApp(
    vm: AppViewModel,
    ringVm: it.anello.app.RingViewModel,
    strapVm: it.anello.app.StrapViewModel,
    motionVm: it.anello.app.MotionViewModel,
    onExportMotion: () -> Unit,
    hasBlePermissions: Boolean,
    onRequestBlePermissions: () -> Unit,
    onExportRaw: () -> Unit,
    onRequestPermissions: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onExportCsv: () -> Unit,
    onImportFit: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(selectedDate)

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Card) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val current = Tab.entries[tab]
                // La scheda Anello (BLE) è indipendente da Health Connect.
                val ringSource = state.settings.source == DataSource.RING
                if (current == Tab.RING) {
                    RingScreen(ringVm, strapVm, motionVm, onExportMotion, hasBlePermissions, onRequestBlePermissions, onExportRaw)
                } else if (current == Tab.SETTINGS) {
                    SettingsScreen(state, vm, onExportCsv)
                } else if (ringSource) {
                    when (current) {
                        Tab.TODAY -> TodayScreen(state, date, { selectedDate = it.toString() }, vm::sync, vm::confirmRebaseline, vm::setDiary)
                        Tab.SLEEP -> SleepScreen(state, date) { selectedDate = it.toString() }
                        Tab.TRENDS -> TrendsScreen(state, vm, onImportFit)
                        else -> {}
                    }
                } else when (state.hcStatus) {
                    HcStatus.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    HcStatus.NOT_INSTALLED, HcStatus.NEEDS_UPDATE -> SetupMessage(
                        title = "Serve Health Connect",
                        body = "Le schede Oggi, Sonno e Andamento leggono i dati dall'app Oura tramite Health Connect. Installalo o aggiornalo dal Play Store, poi nell'app Oura attiva la condivisione.\n\nIn alternativa usa la scheda Anello per collegarti direttamente via Bluetooth.",
                        button = "Apri Play Store",
                        onClick = onInstallHealthConnect,
                    )
                    HcStatus.NO_PERMISSIONS -> SetupMessage(
                        title = "Collega i tuoi dati",
                        body = "1. Nell'app Oura: Impostazioni → Health Connect → attiva la condivisione.\n\n2. Qui sotto concedi ad Anello la lettura di sonno, battito, HRV, SpO2, respirazione e attività.\n\nI dati restano solo sul tuo telefono.",
                        button = "Concedi accesso",
                        onClick = onRequestPermissions,
                    )
                    HcStatus.READY -> when (current) {
                        Tab.TODAY -> TodayScreen(state, date, { selectedDate = it.toString() }, vm::sync, vm::confirmRebaseline, vm::setDiary)
                        Tab.SLEEP -> SleepScreen(state, date) { selectedDate = it.toString() }
                        Tab.TRENDS -> TrendsScreen(state, vm, onImportFit)
                        Tab.SETTINGS -> SettingsScreen(state, vm, onExportCsv)
                        Tab.RING -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupMessage(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TextMain)
        Spacer(Modifier.height(16.dp))
        Text(body, color = TextDim, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClick) { Text(button) }
    }
}
