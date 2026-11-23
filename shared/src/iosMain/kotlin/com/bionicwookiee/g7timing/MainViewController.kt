// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.bionicwookiee.g7timing.bench.AdvertListener
import com.bionicwookiee.g7timing.bench.CodeCheckBench
import com.bionicwookiee.g7timing.ble.IosBleCentral
import com.bionicwookiee.g7timing.g7.ApplicatorCodeParser
import com.bionicwookiee.g7timing.monitor.MonitorController
import com.bionicwookiee.g7timing.ui.G7timingApp
import kotlinx.cinterop.BetaInteropApi
import kotlin.time.Clock
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

private val controller by lazy { MonitorController(IosBleCentral(), MainScope()) }

/**
 * Entry point used by the SwiftUI wrapper in iosApp. [scanApplicator] presents the Swift camera
 * scanner; it calls back with the code's text, or with an error message if the camera can't be used.
 */
fun MainViewController(
    scanApplicator: (onCode: (String) -> Unit, onError: (String) -> Unit) -> Unit,
): UIViewController = ComposeUIViewController {
    G7timingApp(
        controller = controller,
        darkTheme = isSystemInDarkTheme(),
        onKeepScreenOn = { on -> UIApplication.sharedApplication.idleTimerDisabled = on },
        onScanApplicator = {
            scanApplicator({ code -> controller.onApplicatorScanned(code) }, { msg -> controller.onApplicatorScanFailed(msg) })
        },
    )
}

/** For the Swift scanner: true if [text] is a G7 applicator or box code (holds a serial number). */
fun isApplicatorCode(text: String): Boolean = ApplicatorCodeParser.parse(text) != null

/**
 * Development only, called from debug builds: runs [CodeCheckBench] against one named sensor,
 * logging to the console (see the console with `devicectl device process launch --console`).
 */
fun startCodeCheckBench(target: String, code: String, runs: Int) {
    UIApplication.sharedApplication.idleTimerDisabled = true
    MainScope().launch {
        CodeCheckBench(IosBleCentral(), target, code, runs) { println("G7Bench: $it") }.run()
    }
}

/**
 * Development only: listens for one named sensor's advertisements without connecting, logging to
 * the console and to Documents/listen.log (copy it off with `devicectl device copy from`).
 */
@OptIn(BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
fun startAdvertListener(target: String) {
    UIApplication.sharedApplication.idleTimerDisabled = true
    val dir = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    val path = "$dir/listen.log"
    val text = StringBuilder()
    MainScope().launch {
        AdvertListener(IosBleCentral(), target, { msg ->
            val line = "${Clock.System.now()} $msg"
            println("G7Listen: $line")
            text.append(line).append('\n')
            NSString.create(string = text.toString()).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        }).run()
    }
}
