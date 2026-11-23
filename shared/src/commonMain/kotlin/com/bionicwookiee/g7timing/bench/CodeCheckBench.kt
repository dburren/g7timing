// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.bench

import com.bionicwookiee.g7timing.ble.BleCentral
import com.bionicwookiee.g7timing.ble.BleException
import com.bionicwookiee.g7timing.ble.GattConnection
import com.bionicwookiee.g7timing.g7.codecheck.CodeCheckOutcome
import com.bionicwookiee.g7timing.g7.codecheck.G7CodeCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Test bench, for development only (started from debug builds): repeatedly runs the sensor-code
 * check against ONE sensor chosen by its advertised name, and reports each result and each
 * wake-up's advertisement bytes through [log]. No other device is connected to. The code itself
 * is never logged.
 */
class CodeCheckBench(
    private val central: BleCentral,
    private val target: String,
    private val code: String,
    private val runs: Int,
    private val log: (String) -> Unit,
) {
    suspend fun run(maxMinutes: Int = 75) {
        require(G7CodeCheck.isSensorCode(code))
        log("Bench: target $target, $runs code checks, up to $maxMinutes min")
        withTimeoutOrNull(maxMinutes * 60_000L) { bench() }
        log("Bench finished")
    }

    private suspend fun bench() = coroutineScope {
        val heard = MutableSharedFlow<String>(extraBufferCapacity = 16)
        var id: String? = null
        var lastAdvert = 0L
        var lastMfr = ""
        val scan = launch {
            while (true) {
                try {
                    withTimeoutOrNull(4 * 60_000L) {
                        central.scan().collect { ad ->
                            if (ad.name != target) return@collect
                            id = ad.id
                            val mfr = ad.dexcomData?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } ?: "none"
                            val now = Clock.System.now().toEpochMilliseconds()
                            if (now - lastAdvert > 20_000 || mfr != lastMfr) log("advert ${ad.rssi} dBm mfr=$mfr")
                            lastAdvert = now
                            lastMfr = mfr
                            heard.tryEmit(ad.id)
                        }
                    }
                } catch (e: BleException) {
                    log("scan error: ${e.message}")
                    delay(5_000)
                }
            }
        }
        var done = 0
        while (done < runs) {
            val dev = id ?: heard.first()
            val conn = connectWhenAwake(dev, heard)
            log("connected (check ${done + 1} of $runs)")
            try {
                val listed = try {
                    conn.discoverServices(); true
                } catch (e: Exception) {
                    log("didn't list services: ${e.message}"); false
                }
                if (listed) {
                    val t0 = Clock.System.now()
                    val outcome = G7CodeCheck(code).run(conn)
                    done++
                    val ms = (Clock.System.now() - t0).inWholeMilliseconds
                    log(
                        "check $done: " + when (outcome) {
                            CodeCheckOutcome.Matches -> "MATCHES"
                            CodeCheckOutcome.DoesNotMatch -> "does not match"
                            is CodeCheckOutcome.Inconclusive -> "no answer (${outcome.reason})"
                        } + " in $ms ms",
                    )
                }
            } finally {
                withContext(NonCancellable) { conn.close() }
            }
            delay(3_000)
        }
        scan.cancel()
    }

    /** Waiting connection plus an immediate one each time the target is heard; first wins. */
    private suspend fun connectWhenAwake(id: String, heard: MutableSharedFlow<String>): GattConnection = coroutineScope {
        val winner = CompletableDeferred<GattConnection>()
        suspend fun offer(c: GattConnection) {
            if (!winner.complete(c)) withContext(NonCancellable) { c.close() }
        }
        val waiting = launch {
            while (true) {
                try {
                    offer(central.connect(id)); return@launch
                } catch (e: BleException) {
                    log("waiting connection failed: ${e.message}"); delay(20_000)
                }
            }
        }
        val direct = launch {
            heard.collect {
                try {
                    offer(central.connect(id, 5_000))
                } catch (e: BleException) {
                    // Missed it; the next advertisement or the waiting connection may catch it.
                }
            }
        }
        try {
            winner.await()
        } finally {
            waiting.cancel(); direct.cancel()
        }
    }
}
