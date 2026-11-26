package it.anello.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.health.connect.client.PermissionController
import it.anello.app.ui.AnelloApp
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val ringVm: RingViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            vm.refreshStatus(syncIfReady = true)
        }

    private val saveCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(vm.exportCsv().toByteArray()) }
            }
        }

    private val saveRaw =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) {
                val f = File(ringVm.exportRawPath())
                if (f.exists()) contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
        }

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBlePermissions(): Boolean =
        blePermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private var blePermsState by mutableStateOf(false)

    private val requestBle =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            blePermsState = hasBlePermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blePermsState = hasBlePermissions()
        it.anello.app.ble.RingSyncWorker.schedule(this, ringVm.state.value.autoSync)
        setContent {
            AnelloApp(
                vm = vm,
                ringVm = ringVm,
                hasBlePermissions = blePermsState,
                onRequestBlePermissions = { requestBle.launch(blePermissions()) },
                onExportRaw = { saveRaw.launch("anello-eventi-grezzi.jsonl") },
                onRequestPermissions = { requestPermissions.launch(vm.repo.permissions) },
                onInstallHealthConnect = {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.apps.healthdata")
                        )
                    )
                },
                onExportCsv = { saveCsv.launch("anello-dati.csv") },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        blePermsState = hasBlePermissions()
        vm.refreshStatus(syncIfReady = true)
        if (blePermsState) ringVm.syncOnOpen()
    }
}
