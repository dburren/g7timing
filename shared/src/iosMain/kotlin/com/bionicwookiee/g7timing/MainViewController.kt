// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import com.bionicwookiee.g7timing.ble.IosBleCentral
import com.bionicwookiee.g7timing.monitor.MonitorController
import com.bionicwookiee.g7timing.ui.G7timingApp
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

private val controller by lazy { MonitorController(IosBleCentral(), MainScope()) }

/** Entry point used by the SwiftUI wrapper in iosApp. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    G7timingApp(
        controller = controller,
        darkTheme = isSystemInDarkTheme(),
        onKeepScreenOn = { on -> UIApplication.sharedApplication.idleTimerDisabled = on },
    )
}
