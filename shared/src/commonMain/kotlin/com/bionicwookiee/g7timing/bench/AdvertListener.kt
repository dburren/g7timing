// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.bench

import com.bionicwookiee.g7timing.ble.BleCentral
import com.bionicwookiee.g7timing.ble.BleException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Test bench, for development only: listens for ONE sensor's advertisements by name and never
 * connects to anything. Logs each wake-up (the first advertisement after a quiet spell) with the
 * time since the previous one, and a heartbeat every [heartbeatMinutes] so a long silence shows.
 */
class AdvertListener(
    private val central: BleCentral,
    private val target: String,
    private val log: (String) -> Unit,
    private val heartbeatMinutes: Int = 10,
) {
    suspend fun run(): Nothing {
        log("Listening for $target (no connections)")
        var lastHeard = 0L
        var lastBeat = Clock.System.now().toEpochMilliseconds()
        while (true) {
            try {
                withTimeoutOrNull(60_000L) {
                    central.scan().collect { ad ->
                        if (ad.name != target) return@collect
                        val now = Clock.System.now().toEpochMilliseconds()
                        if (now - lastHeard > 20_000) {
                            val mfr = ad.dexcomData?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } ?: "none"
                            val gap = if (lastHeard == 0L) "first" else "after ${(now - lastHeard) / 1000} s"
                            log("woke ($gap) ${ad.rssi} dBm mfr=$mfr")
                        }
                        lastHeard = now
                    }
                }
            } catch (e: BleException) {
                log("scan error: ${e.message}")
                delay(5_000)
            }
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastBeat >= heartbeatMinutes * 60_000L) {
                val since = if (lastHeard == 0L) "never heard" else "last heard ${(now - lastHeard) / 1000} s ago"
                log("still listening, $since")
                lastBeat = now
            }
        }
    }
}
