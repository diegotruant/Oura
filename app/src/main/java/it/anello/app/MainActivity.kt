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
import it.anello.app.data.Backup
import it.anello.app.ui.AnelloApp
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val ringVm: RingViewModel by viewModels()
    private val strapVm: StrapViewModel by viewModels()
    private val motionVm: MotionViewModel by viewModels()
    private val devicesVm: DevicesViewModel by viewModels()

    private val saveMotionWindows =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri != null) Thread {
                val csv = motionVm.exportWindowsCsv()
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
            }.start()
        }

    private val saveMotion =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri != null) Thread {
                val csv = motionVm.exportCsv()
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }
            }.start()
        }

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

    private val pickFit =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@registerForActivityResult
            Thread {
                // Legge i file fuori dal thread principale; limite 50 MB per file.
                val files = uris.take(50).mapNotNull { uri ->
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readBytes()
                            if (bytes.size <= 50_000_000) bytes else null
                        }
                    }.getOrNull()
                }
                runOnUiThread { vm.importFit(files) }
            }.start()
        }

    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null) Thread {
                val r = runCatching { contentResolver.openOutputStream(uri)?.use { Backup.write(this, it) } }
                runOnUiThread {
                    android.widget.Toast.makeText(this, if (r.getOrNull() != null) "Backup salvato." else "Backup non riuscito.", android.widget.Toast.LENGTH_LONG).show()
                }
            }.start()
        }

    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) Thread {
                val r = runCatching { contentResolver.openInputStream(uri)?.use { Backup.restore(this, it) } }
                runOnUiThread {
                    if (r.getOrNull() != null) {
                        // I dati in memoria sono vecchi: si riparte da capo.
                        packageManager.getLaunchIntentForPackage(packageName)?.let {
                            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        }
                        Runtime.getRuntime().exit(0)
                    } else {
                        android.widget.Toast.makeText(this, r.exceptionOrNull()?.message ?: "Ripristino non riuscito.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

    private val requestSteps =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.sync() }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                strapVm = strapVm,
                motionVm = motionVm,
                devicesVm = devicesVm,
                onExportMotion = { saveMotion.launch("anello-movimento.csv") },
                onExportMotionWindows = { saveMotionWindows.launch("anello-movimento-finestre.csv") },
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
                onImportFit = { pickFit.launch(arrayOf("*/*")) },
                onBackup = { saveBackup.launch("anello-backup-${java.time.LocalDate.now()}.zip") },
                onRestore = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onRequestSteps = {
                    if (Build.VERSION.SDK_INT >= 29) requestSteps.launch(android.Manifest.permission.ACTIVITY_RECOGNITION) else vm.sync()
                },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
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
