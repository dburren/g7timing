// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bionicwookiee.g7timing.ble.AndroidBleCentral
import com.bionicwookiee.g7timing.monitor.MonitorController
import com.bionicwookiee.g7timing.ui.G7timingApp

/** Survives configuration changes so a running session isn't interrupted by rotation. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val central = AndroidBleCentral(app)
    val controller = MonitorController(central, viewModelScope)

    override fun onCleared() {
        controller.stop()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                vm.central.refreshAvailability()
            }
            LaunchedEffect(Unit) {
                if (!AndroidBleCentral.hasPermissions(this@MainActivity)) {
                    launcher.launch(AndroidBleCentral.requiredPermissions())
                }
            }
            val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                val data = r.data ?: return@rememberLauncherForActivityResult
                data.getStringExtra(ScanApplicatorActivity.EXTRA_CODE)?.let { vm.controller.onApplicatorScanned(it) }
                data.getStringExtra(ScanApplicatorActivity.EXTRA_ERROR)?.let { vm.controller.onApplicatorScanFailed(it) }
            }
            G7timingApp(
                controller = vm.controller,
                onScanApplicator = { scanLauncher.launch(Intent(this, ScanApplicatorActivity::class.java)) },
                darkTheme = isSystemInDarkTheme(),
                onKeepScreenOn = { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.central.refreshAvailability()
    }
}
