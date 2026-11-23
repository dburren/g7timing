// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bionicwookiee.g7timing.ble.AndroidBleCentral
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only bench: repeatedly runs the sensor-code check against ONE sensor, chosen by its
 * advertised name, and logs every result plus each wake-up's advertisement bytes. No other
 * device is ever connected to. Start it with:
 *
 *   adb shell am start -n com.bionicwookiee.g7timing/.CodeCheckBenchActivity \
 *     --es target DXCMxx --es code NNNN --ei runs 6
 *
 * Results go to logcat (tag G7Bench) and the screen. The code itself is never logged.
 */
class CodeCheckBenchActivity : ComponentActivity() {
    private lateinit var text: TextView
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(24, 96, 24, 24) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(text) })

        val target = intent.getStringExtra("target")
        val code = intent.getStringExtra("code")
        val runs = intent.getIntExtra("runs", 6)
        val maxMinutes = intent.getIntExtra("maxMinutes", 75)
        if (target.isNullOrBlank() || code == null || !G7CodeCheck.isSensorCode(code)) {
            log("Needs --es target <name> --es code <4 digits>")
            return
        }
        log("Bench: target $target, $runs code checks, up to $maxMinutes min")
        lifecycleScope.launch { withTimeoutOrNull(maxMinutes * 60_000L) { bench(target, code, runs) }; log("Bench finished") }
    }

    private suspend fun bench(target: String, code: String, runs: Int) = coroutineScope {
        val central = AndroidBleCentral(this@CodeCheckBenchActivity)
        val heard = MutableSharedFlow<String>(extraBufferCapacity = 16)
        var id: String? = null
        var lastAdvert = 0L
        var lastMfr = ""
        val scan = launch {
            while (true) {
                withTimeoutOrNull(4 * 60_000L) {
                    central.scan().collect { ad ->
                        if (ad.name != target) return@collect
                        id = ad.id
                        val mfr = ad.dexcomData?.joinToString("") { "%02x".format(it) } ?: "none"
                        val now = System.currentTimeMillis()
                        if (now - lastAdvert > 20_000 || mfr != lastMfr) log("advert ${ad.rssi} dBm mfr=$mfr")
                        lastAdvert = now
                        lastMfr = mfr
                        heard.tryEmit(ad.id)
                    }
                }
            }
        }
        var done = 0
        while (done < runs) {
            val dev = id ?: heard.first()
            val conn = connectWhenAwake(central, dev, heard)
            log("connected (check ${done + 1} of $runs)")
            try {
                val services = try { conn.discoverServices() } catch (e: Exception) { log("didn't list services: ${e.message}"); null }
                if (services != null) {
                    val t0 = System.currentTimeMillis()
                    val outcome = G7CodeCheck(code).run(conn)
                    done++
                    val ms = System.currentTimeMillis() - t0
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
    private suspend fun connectWhenAwake(central: AndroidBleCentral, id: String, heard: MutableSharedFlow<String>): GattConnection = coroutineScope {
        val winner = CompletableDeferred<GattConnection>()
        suspend fun offer(c: GattConnection) { if (!winner.complete(c)) withContext(NonCancellable) { c.close() } }
        val waiting = launch {
            while (true) {
                try { offer(central.connect(id)); return@launch } catch (e: BleException) { log("waiting connection failed: ${e.message}"); delay(20_000) }
            }
        }
        val direct = launch {
            heard.collect {
                try { offer(central.connect(id, 5_000)) } catch (e: BleException) { log("immediate connection failed: ${e.message}") }
            }
        }
        try { winner.await() } finally { waiting.cancel(); direct.cancel() }
    }

    private fun log(msg: String) {
        Log.i("G7Bench", msg)
        val line = "${fmt.format(Date())}  $msg"
        runOnUiThread {
            lines.addLast(line)
            while (lines.size > 200) lines.removeFirst()
            text.text = lines.joinToString("\n")
        }
    }
}
