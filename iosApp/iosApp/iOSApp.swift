// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        #if DEBUG
        // Development bench: launch with -benchTarget DXCMxx -benchCode NNNN -benchRuns N.
        let d = UserDefaults.standard
        if let target = d.string(forKey: "benchTarget"), let code = d.string(forKey: "benchCode") {
            let runs = d.integer(forKey: "benchRuns")
            MainViewControllerKt.startCodeCheckBench(target: target, code: code, runs: Int32(runs > 0 ? runs : 6))
        } else if let target = d.string(forKey: "listenTarget") {
            // Listen-only: -listenTarget DXCMxx (never connects).
            MainViewControllerKt.startAdvertListener(target: target)
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
