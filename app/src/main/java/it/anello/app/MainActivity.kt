package it.anello.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.health.connect.client.PermissionController
import it.anello.app.ui.AnelloApp

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnelloApp(
                vm = vm,
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
        vm.refreshStatus(syncIfReady = true)
    }
}
