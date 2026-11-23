// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.monitor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.bionicwookiee.g7timing.ble.BleAdvertisement
import com.bionicwookiee.g7timing.ble.BleCentral
import com.bionicwookiee.g7timing.ble.BleException
import com.bionicwookiee.g7timing.ble.CheckUnavailableException
import com.bionicwookiee.g7timing.ble.GattConnection
import com.bionicwookiee.g7timing.g7.ApplicatorCodeParser
import com.bionicwookiee.g7timing.g7.G7Protocol
import com.bionicwookiee.g7timing.g7.codecheck.CodeCheckOutcome
import com.bionicwookiee.g7timing.g7.codecheck.G7CodeCheck
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

enum class Phase { Idle, Running }

enum class TransmitterStatus {
    /** Heard, signal strong enough; waiting for it to wake so G7timing can connect. */
    WaitingToConnect,
    /** Heard, but weaker than the signal floor, so G7timing won't connect to it. */
    TooFaint,
    /** Connected; waiting to see whether it answers. */
    Checking,
    /** Answered after connecting: a working sensor. Left alone from then on. */
    Working,
    /** Accepted a connection but answered nothing: what an unworn sensor in its applicator does. */
    CantBeChecked,
    /** Accepted a connection, but this phone couldn't make it answer for itself. Left alone. */
    CouldNotSort,
}

/** Whether a transmitter uses the sensor code being looked for. */
enum class CodeStatus {
    /** Not checked yet. */
    NotChecked,
    /** A code check is running now. */
    Checking,
    /** It proved it uses the code. */
    Matches,
    /** It answered, and uses a different code. */
    DoesNotMatch,
    /** Two tries gave no answer either way. */
    GaveUp,
}

/** A G7 transmitter seen this session. */
data class Transmitter(
    val id: String,
    val name: String?,
    val status: TransmitterStatus,
    /** Last time the transmitter was seen: an advertisement heard, or a connection it accepted. */
    val lastSeenAt: Instant,
    /** Signal strength at [lastSeenAt], dBm; null when it was seen only by connecting. */
    val lastSeenRssi: Int?,
    val lastConnectionAt: Instant? = null,
    /** The connection before [lastConnectionAt], if there has been one. */
    val previousConnectionAt: Instant? = null,
    val connectionCount: Int = 0,
    /** First advertisement heard in the latest wake-up (a burst of advertisements). */
    val wakeStartedAt: Instant? = null,
    /** First advertisement heard in the wake-up before that. */
    val previousWakeStartedAt: Instant? = null,
    /** Serial-number check value from its advertisement (see [G7Protocol.advertisedSerialCrc]). */
    val serialCrc: Int? = null,
    /** Whether it uses the sensor code being looked for, while one is. */
    val codeStatus: CodeStatus = CodeStatus.NotChecked,
    /** Code checks started on it for the current sensor code. */
    val codeAttempts: Int = 0,
) {
    /** Time between the last two connections, or null until there have been two. */
    val lastInterval: Duration?
        get() = lastConnectionAt?.let { last -> previousConnectionAt?.let { last - it } }

    /**
     * Time between the starts of the last two wake-ups heard by the scan, or null until two have
     * been heard. Used for transmitters G7timing doesn't keep connecting to, such as working sensors.
     */
    val lastWakeInterval: Duration?
        get() = wakeStartedAt?.let { last -> previousWakeStartedAt?.let { last - it } }
}

/**
 * The sensor being looked for, from its applicator or box code or a typed serial number. The full
 * serial is held only in memory, so the screen can show it for checking against what's printed;
 * it's never saved or logged. Everywhere else the sensor is named by its last 4 digits.
 */
data class ScannedApplicator(
    val serial: String,
    /** True if typed by hand (so it may be mistyped), false if read from the applicator or box. */
    val typed: Boolean = false,
    val serialCrc: Int = G7Protocol.serialCrc(serial),
) {
    val serialEnding: String get() = serial.takeLast(4)

    /** How the UI names it, e.g. "sensor …4186". */
    val label: String get() = "sensor …$serialEnding"

    /** The serial in groups of 4 digits, for reading and checking, e.g. "1000 0000 4186". */
    val serialGrouped: String get() = serial.chunked(4).joinToString(" ")

    // Keeps the full serial out of anything that prints the state.
    override fun toString(): String = "ScannedApplicator($label, crc=$serialCrc)"
}

data class UiState(
    val phase: Phase = Phase.Idle,
    /** When Start was pressed; kept until Stop. */
    val startedAt: Instant? = null,
    val transmitters: List<Transmitter> = emptyList(),
    val log: List<String> = emptyList(),
    /** The serial number being looked for (scanned or typed), if any. Kept across Start/Stop until cleared. */
    val applicator: ScannedApplicator? = null,
    /**
     * The 4-digit sensor code being looked for, if any (instead of a serial number). Held only in
     * memory, never saved or logged.
     */
    val sensorCode: String? = null,
    /** Why the last scan or entry wasn't accepted, if it wasn't. */
    val applicatorError: String? = null,
) {
    /** True if [t] is the sensor being looked for: it advertises the serial number, or proved it uses the code. */
    fun isTarget(t: Transmitter): Boolean = when {
        applicator != null -> t.serialCrc != null && t.serialCrc == applicator.serialCrc
        sensorCode != null -> t.codeStatus == CodeStatus.Matches
        else -> false
    }

    /** Whether [t]'s code still needs checking (a code is set, and it hasn't answered or used up its tries). */
    fun needsCodeCheck(t: Transmitter, maxAttempts: Int): Boolean =
        sensorCode != null && t.codeStatus != CodeStatus.Matches && t.codeStatus != CodeStatus.DoesNotMatch &&
            t.codeStatus != CodeStatus.Checking && t.codeAttempts < maxAttempts

    // Keeps the code out of anything that prints the state.
    override fun toString(): String =
        "UiState(phase=$phase, transmitters=${transmitters.size}, applicator=$applicator, sensorCode=${if (sensorCode != null) "set" else "none"})"
}

class MonitorSettings(
    /** Don't connect to G7s weaker than this. Sensors in their applicator are faint (−85 to −102 dBm seen with one against the phone). */
    val minRssi: Int = -99,
    /** After a connection to a transmitter, wait this long before waiting for the next one, so one wake-up isn't counted twice. */
    val reconnectCooldownMs: Long = 15_000,
    /** After a waiting connection fails outright, wait this long before re-arming it. */
    val retryDelayMs: Long = 20_000,
    /**
     * Restart the scan this often. Android throttles a long-running scan: on a Moto g55
     * (Android 16) it was cut to a low duty cycle after a few minutes, after which brief G7
     * advertisements were missed. Restarting keeps it at full speed.
     */
    val scanRestartMs: Long = 4 * 60_000,
    /** Timeout for the immediate connection tried when the scan hears a G7 that's awake right now. */
    val directConnectTimeoutMs: Long = 5_000,
    /**
     * How many more times to check whether the scanned applicator's transmitter responds, after
     * it first didn't. A working sensor doesn't always answer a second phone.
     */
    val scannedRechecks: Int = 3,
    /** How many times to try a sensor code on each transmitter that responds. */
    val codeCheckAttempts: Int = 2,
)

/**
 * Shows every G7 transmitter seen, sorts each one with a single connection, and keeps
 * reporting the ones that accept a connection but can't be checked. All state is exposed
 * through [state]; the UI calls [start] and [stop].
 *
 * Every G7 heard at a usable signal is connected to once and asked to list its services
 * (answered by the device itself, never a cache). If it answers, it's a working sensor (possibly
 * someone's): it's disconnected straight away and never contacted again in the session. If it
 * accepts the connection but answers nothing, G7timing keeps connecting each time it wakes,
 * disconnecting immediately.
 *
 * Sleeping G7s wake only briefly, so connections are made two ways at once: see [connectWhenAwake].
 */
class MonitorController(
    private val central: BleCentral,
    private val scope: CoroutineScope,
    private val settings: MonitorSettings = MonitorSettings(),
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val availability = central.availability
    val identifierLabel: String get() = central.identifierLabel

    private var session: Job? = null

    /** IDs of G7s the scan has just heard, i.e. transmitters that are awake right now. */
    private val heard = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lastHeard = mutableMapOf<String, Instant>()

    /** Code-check jobs for working sensors, by transmitter ID. */
    private val codeCheckers = mutableMapOf<String, Job>()

    /** Starts a new session. Nothing carries over from earlier sessions. */
    fun start() {
        stop()
        _state.value = UiState(
            phase = Phase.Running,
            startedAt = clock.now(),
            applicator = _state.value.applicator,
            sensorCode = _state.value.sensorCode,
        )
        log("Started")
        session = scope.launch { runSession() }
    }

    fun stop() {
        // Cancelling the session withdraws every waiting connection.
        session?.cancel()
        session = null
        lastHeard.clear()
        codeCheckers.clear()
        if (_state.value.phase != Phase.Idle) {
            _state.value = UiState(phase = Phase.Idle, applicator = _state.value.applicator, sensorCode = _state.value.sensorCode)
        }
    }

    /**
     * Takes the text read from an applicator's 2D code. Returns true if it held a serial number.
     * The serial is shown on screen but never saved or logged.
     */
    fun onApplicatorScanned(raw: String): Boolean {
        val code = ApplicatorCodeParser.parse(raw)
        if (code == null) {
            _state.update { it.copy(applicatorError = "That code isn't from a G7 applicator or box (no serial number found).") }
            return false
        }
        useSerial(code.serial, "Scanned")
        return true
    }

    /**
     * Takes a serial number typed in by hand (spaces and dashes are ignored). Returns true if it
     * looks like one. As with a scan, it's shown on screen but never saved or logged.
     */
    fun onSerialEntered(text: String): Boolean {
        val serial = ApplicatorCodeParser.parseTypedSerial(text)
        if (serial == null) {
            _state.update {
                it.copy(applicatorError = "That doesn't look like a serial number. Type the serial number printed on the applicator or box (usually 12 digits).")
            }
            return false
        }
        useSerial(serial, "Entered")
        return true
    }

    private fun useSerial(serial: String, how: String) {
        val applicator = ScannedApplicator(serial, typed = how == "Entered")
        stopCodeChecks()
        _state.update { it.copy(applicator = applicator, sensorCode = null, applicatorError = null) }
        if (_state.value.phase == Phase.Running) {
            log("$how ${applicator.label}")
            _state.value.transmitters.firstOrNull { _state.value.isTarget(it) }
                ?.let { log("${label(it.id)} is ${applicator.label}") }
        }
    }

    /**
     * Takes a 4-digit sensor code typed in by hand, replacing any serial number being looked for.
     * Returns true if it's 4 digits. Each transmitter that responds is then checked for it (see
     * [G7CodeCheck]). The code is shown on screen but never saved or logged.
     */
    fun onSensorCodeEntered(text: String): Boolean {
        val code = text.filterNot { it == ' ' }
        if (!G7CodeCheck.isSensorCode(code)) {
            _state.update { it.copy(applicatorError = "A sensor code is the 4 digits printed on the applicator.") }
            return false
        }
        stopCodeChecks()
        _state.update { st ->
            st.copy(
                applicator = null,
                sensorCode = code,
                applicatorError = null,
                transmitters = st.transmitters.map { it.copy(codeStatus = CodeStatus.NotChecked, codeAttempts = 0) },
            )
        }
        if (_state.value.phase == Phase.Running) log("Looking for the sensor with the code entered")
        return true
    }

    /** Shows why a scan couldn't happen (e.g. camera permission refused). */
    fun onApplicatorScanFailed(message: String) {
        _state.update { it.copy(applicatorError = message) }
    }

    /** Stops looking for a particular sensor (serial number or code). */
    fun clearApplicator() {
        stopCodeChecks()
        _state.update { it.copy(applicator = null, sensorCode = null, applicatorError = null) }
    }

    private fun stopCodeChecks() {
        codeCheckers.values.forEach { it.cancel() }
        codeCheckers.clear()
    }

    private suspend fun runSession() = coroutineScope {
        // The scan finds G7s and keeps signal strengths current. Restart it before Android
        // throttles it (see MonitorSettings.scanRestartMs).
        launch {
            while (isActive) {
                try {
                    withTimeoutOrNull(settings.scanRestartMs) { central.scan().collect { onAdvertisement(it) } }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log("Scan error: ${e.message}")
                    delay(5_000)
                }
            }
        }
        // One watcher per G7 once it's heard at a usable signal.
        val watched = mutableSetOf<String>()
        while (true) {
            val st = _state.value
            for (t in st.transmitters) {
                if (t.status == TransmitterStatus.WaitingToConnect && watched.add(t.id)) launch { watch(t.id) }
                // Working sensors are otherwise left alone; one whose code still needs checking gets
                // its own connections for that (the first check usually happens on the connection
                // that found it working).
                if (t.status == TransmitterStatus.Working && st.needsCodeCheck(t, settings.codeCheckAttempts) &&
                    codeCheckers[t.id]?.isActive != true
                ) {
                    codeCheckers[t.id] = launch { checkCodeWhenAwake(t.id) }
                }
            }
            delay(500)
        }
    }

    private fun onAdvertisement(ad: BleAdvertisement) {
        if (!G7Protocol.looksLikeG7(ad.name, ad.serviceUuids)) return
        val now = clock.now()
        val known = transmitter(ad.id)
        // A G7 advertises in short bursts when it wakes. A gap longer than WAKE_GAP_MS since the
        // previous advertisement means this one starts a new wake-up.
        val newWake = lastHeard[ad.id]?.let { (now - it).inWholeMilliseconds > WAKE_GAP_MS } ?: true
        lastHeard[ad.id] = now
        heard.tryEmit(ad.id)
        val strongEnough = ad.rssi >= settings.minRssi
        val crc = G7Protocol.advertisedSerialCrc(ad.dexcomData)
        val newlyMatched = crc != null && crc != known?.serialCrc && crc == _state.value.applicator?.serialCrc
        if (known == null) {
            val status = if (strongEnough) TransmitterStatus.WaitingToConnect else TransmitterStatus.TooFaint
            _state.update {
                it.copy(
                    transmitters = it.transmitters + Transmitter(ad.id, ad.name, status, now, ad.rssi, wakeStartedAt = now, serialCrc = crc),
                )
            }
            log("${ad.name ?: ad.id} seen (${ad.rssi} dBm)" + if (strongEnough) "" else ": too faint to connect")
            if (newlyMatched) log("${label(ad.id)} is ${_state.value.applicator!!.label}")
            return
        }
        updateTransmitter(ad.id) {
            it.copy(
                name = ad.name ?: it.name,
                lastSeenAt = now,
                lastSeenRssi = ad.rssi,
                // A faint transmitter that comes closer becomes eligible.
                status = if (it.status == TransmitterStatus.TooFaint && strongEnough) TransmitterStatus.WaitingToConnect else it.status,
                previousWakeStartedAt = if (newWake) it.wakeStartedAt else it.previousWakeStartedAt,
                wakeStartedAt = if (newWake) now else it.wakeStartedAt,
                serialCrc = crc ?: it.serialCrc,
            )
        }
        if (newlyMatched) log("${label(ad.id)} is ${_state.value.applicator!!.label}")
        if (newWake && known.status != TransmitterStatus.CantBeChecked) {
            val interval = known.wakeStartedAt?.let { " (${formatMinutesSeconds((now - it).inWholeSeconds)} since the last)" }.orEmpty()
            log("${label(ad.id)} woke, ${ad.rssi} dBm$interval")
        }
        if (known.status == TransmitterStatus.TooFaint && strongEnough) log("${label(ad.id)} now strong enough (${ad.rssi} dBm)")
    }

    /** Sorts one G7 on its first connection, then (if it can't be checked) records every wake-up. */
    private suspend fun watch(id: String) {
        val first = connectWhenAwake(id)
        val connectedAt = clock.now()
        recordConnection(id, connectedAt, TransmitterStatus.Checking)
        log("${label(id)} accepted a connection; seeing if it responds")
        val result = try {
            first.discoverServices()
            // It responds: while it's connected, check the sensor code if one is being looked for.
            checkCodeOn(id, first)
            TransmitterStatus.Working
        } catch (e: BleException) {
            TransmitterStatus.CantBeChecked
        } catch (e: CheckUnavailableException) {
            log("${label(id)}: ${e.message}")
            TransmitterStatus.CouldNotSort
        } finally {
            withContext(NonCancellable) { first.close() }
        }
        updateTransmitter(id) { it.copy(status = result) }
        log(
            when (result) {
                TransmitterStatus.Working -> "${label(id)} responded: working sensor, left alone"
                TransmitterStatus.CantBeChecked -> "${label(id)} accepted a connection but didn't respond"
                else -> "${label(id)} couldn't be tested on this phone; left alone"
            },
        )
        if (result != TransmitterStatus.CantBeChecked) return

        // Not answering once isn't conclusive: a working sensor busy with its own display, or one
        // inserted after the first check, can miss it. Only the transmitter being looked for (or,
        // with a sensor code, any whose code is still unknown) is re-checked, a limited number of
        // times, since each check holds the connection open longer.
        var rechecks = 0
        while (true) {
            delay(settings.reconnectCooldownMs) // don't count the same wake-up twice
            val conn = connectWhenAwake(id)
            val at = clock.now()
            val recheck = rechecks < settings.scannedRechecks && transmitter(id)?.let {
                _state.value.isTarget(it) || _state.value.needsCodeCheck(it, settings.codeCheckAttempts)
            } == true
            val answered = try {
                if (recheck) {
                    rechecks++
                    conn.discoverServices()
                    checkCodeOn(id, conn)
                    true
                } else {
                    false
                }
            } catch (e: BleException) {
                false
            } catch (e: CheckUnavailableException) {
                false
            } finally {
                withContext(NonCancellable) { conn.close() }
            }
            if (answered) {
                recordConnection(id, at, TransmitterStatus.Working)
                log("${label(id)} responded when checked again: working sensor, left alone")
                return
            }
            recordConnection(id, at, TransmitterStatus.CantBeChecked)
            log("${label(id)} accepted a connection" + if (recheck) " but didn't respond (check $rechecks of ${settings.scannedRechecks})" else "")
        }
    }

    /**
     * For a working sensor whose code still needs checking: connects on its next wake-ups and
     * checks it, until it answers or has had its tries.
     */
    private suspend fun checkCodeWhenAwake(id: String) {
        while (transmitter(id)?.let { _state.value.needsCodeCheck(it, settings.codeCheckAttempts) } == true) {
            if ((transmitter(id)?.codeAttempts ?: 0) > 0) delay(settings.reconnectCooldownMs)
            val conn = connectWhenAwake(id)
            try {
                conn.discoverServices()
                checkCodeOn(id, conn)
            } catch (e: BleException) {
                countCodeAttempt(id, "it didn't respond")
            } catch (e: CheckUnavailableException) {
                countCodeAttempt(id, e.message ?: "it couldn't be tested on this phone")
            } finally {
                withContext(NonCancellable) { conn.close() }
            }
        }
    }

    /**
     * If a sensor code is being looked for and [id]'s is still unknown, checks it on [conn] (which
     * has just listed its services) and records the result. The caller disconnects afterwards.
     */
    private suspend fun checkCodeOn(id: String, conn: GattConnection) {
        val code = _state.value.sensorCode ?: return
        val t = transmitter(id) ?: return
        if (!_state.value.needsCodeCheck(t, settings.codeCheckAttempts)) return
        val attempt = t.codeAttempts + 1
        updateTransmitter(id) { it.copy(codeStatus = CodeStatus.Checking, codeAttempts = attempt) }
        log("${label(id)}: checking its sensor code (try $attempt of ${settings.codeCheckAttempts})")
        val outcome = try {
            G7CodeCheck(code).run(conn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (_state.value.sensorCode == code) updateTransmitter(id) { it.copy(codeStatus = CodeStatus.NotChecked, codeAttempts = attempt - 1) }
            throw e
        }
        // The code may have been changed or cleared meanwhile: the result is about the old one.
        if (_state.value.sensorCode != code) return
        when (outcome) {
            CodeCheckOutcome.Matches -> {
                updateTransmitter(id) { it.copy(codeStatus = CodeStatus.Matches) }
                log("${label(id)} uses the sensor code")
            }
            CodeCheckOutcome.DoesNotMatch -> {
                updateTransmitter(id) { it.copy(codeStatus = CodeStatus.DoesNotMatch) }
                log("${label(id)} doesn't use the sensor code")
            }
            is CodeCheckOutcome.Inconclusive -> {
                val gaveUp = attempt >= settings.codeCheckAttempts
                updateTransmitter(id) { it.copy(codeStatus = if (gaveUp) CodeStatus.GaveUp else CodeStatus.NotChecked) }
                log("${label(id)}: code check gave no answer (${outcome.reason})" + if (gaveUp) "; no more tries" else "")
            }
        }
    }

    /** A connection meant for a code check that didn't get as far as checking still uses up a try. */
    private fun countCodeAttempt(id: String, reason: String) {
        val t = transmitter(id) ?: return
        val attempt = t.codeAttempts + 1
        val gaveUp = attempt >= settings.codeCheckAttempts
        updateTransmitter(id) { it.copy(codeAttempts = attempt, codeStatus = if (gaveUp) CodeStatus.GaveUp else CodeStatus.NotChecked) }
        log("${label(id)}: code check not done, $reason (try $attempt of ${settings.codeCheckAttempts})" + if (gaveUp) "; no more tries" else "")
    }

    private fun recordConnection(id: String, at: Instant, status: TransmitterStatus) {
        val rssiNow = lastHeard[id]?.takeIf { (at - it).inWholeMilliseconds <= RECENTLY_HEARD_MS }?.let { transmitter(id)?.lastSeenRssi }
        updateTransmitter(id) {
            it.copy(
                status = status,
                lastSeenAt = at,
                lastSeenRssi = rssiNow,
                previousConnectionAt = it.lastConnectionAt,
                lastConnectionAt = at,
                connectionCount = it.connectionCount + 1,
            )
        }
    }

    /**
     * Connects to [id] the next time it wakes, two ways at once, and returns whichever connects
     * first:
     *  - a waiting connection, handed to the Bluetooth controller (catches wake-ups even between
     *    scan windows, but listens at a low duty cycle and can miss a faint, short burst);
     *  - an immediate connection, started whenever the full-speed scan hears the transmitter.
     * A connection that fails is simply re-armed.
     */
    private suspend fun connectWhenAwake(id: String): GattConnection = coroutineScope {
        val winner = CompletableDeferred<GattConnection>()
        suspend fun offer(c: GattConnection) {
            if (!winner.complete(c)) withContext(NonCancellable) { c.close() }
        }
        val waiting = launch {
            while (true) {
                try {
                    offer(central.connect(id))
                    return@launch
                } catch (e: BleException) {
                    delay(settings.retryDelayMs)
                }
            }
        }
        val direct = launch {
            suspend fun tryNow() {
                try {
                    offer(central.connect(id, settings.directConnectTimeoutMs))
                } catch (e: BleException) {
                    // Missed it this time; the next advertisement or the waiting connection may catch it.
                }
            }
            // Heard a moment ago (e.g. the advertisement that made it a candidate): it may still be awake.
            if (lastHeard[id]?.let { (clock.now() - it).inWholeMilliseconds <= RECENTLY_HEARD_MS } == true) tryNow()
            heard.filter { it == id }.collect { tryNow() }
        }
        try {
            winner.await()
        } finally {
            waiting.cancel()
            direct.cancel()
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun transmitter(id: String) = _state.value.transmitters.firstOrNull { it.id == id }

    private fun label(id: String) = transmitter(id)?.name ?: id

    private fun updateTransmitter(id: String, f: (Transmitter) -> Transmitter) {
        _state.update { st -> st.copy(transmitters = st.transmitters.map { if (it.id == id) f(it) else it }) }
    }

    private fun log(msg: String) {
        val t = clock.now().toLocalTimeString()
        _state.update { it.copy(log = (it.log + "$t  $msg").takeLast(100)) }
    }

    private companion object {
        const val RECENTLY_HEARD_MS = 3_000L

        /** A gap between advertisements longer than this starts a new wake-up. A wake-up's burst lasted about 6 s. */
        const val WAKE_GAP_MS = 20_000L
    }
}
