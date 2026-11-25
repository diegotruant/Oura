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
    TRENDS("Andamento", Icons.Filled.DateRange),
    SETTINGS("Impostazioni", Icons.Filled.Settings),
}

@Composable
fun AnelloApp(
    vm: AppViewModel,
    onRequestPermissions: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onExportCsv: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(selectedDate)

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                if (state.hcStatus == HcStatus.READY) {
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
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (state.hcStatus) {
                    HcStatus.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    HcStatus.NOT_INSTALLED, HcStatus.NEEDS_UPDATE -> SetupMessage(
                        title = "Serve Health Connect",
                        body = "L'app legge i dati dell'anello da Health Connect. Installalo o aggiornalo dal Play Store, poi nell'app Oura attiva la condivisione con Health Connect.",
                        button = "Apri Play Store",
                        onClick = onInstallHealthConnect,
                    )
                    HcStatus.NO_PERMISSIONS -> SetupMessage(
                        title = "Collega i tuoi dati",
                        body = "1. Nell'app Oura: Impostazioni → Health Connect → attiva la condivisione.\n\n2. Qui sotto concedi ad Anello la lettura di sonno, battito, HRV, SpO2, respirazione e attività.\n\nI dati restano solo sul tuo telefono.",
                        button = "Concedi accesso",
                        onClick = onRequestPermissions,
                    )
                    HcStatus.READY -> when (Tab.entries[tab]) {
                        Tab.TODAY -> TodayScreen(state, date, { selectedDate = it.toString() }, vm::sync)
                        Tab.SLEEP -> SleepScreen(state, date) { selectedDate = it.toString() }
                        Tab.TRENDS -> TrendsScreen(state)
                        Tab.SETTINGS -> SettingsScreen(state, vm, onExportCsv)
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
