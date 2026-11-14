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
import com.bionicwookiee.g7timing.g7.G7Protocol
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

data class UiState(
    val phase: Phase = Phase.Idle,
    /** When Start was pressed; kept until Stop. */
    val startedAt: Instant? = null,
    val transmitters: List<Transmitter> = emptyList(),
    val log: List<String> = emptyList(),
)

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

    /** Starts a new session. Nothing carries over from earlier sessions. */
    fun start() {
        stop()
        _state.value = UiState(phase = Phase.Running, startedAt = clock.now())
        log("Started")
        session = scope.launch { runSession() }
    }

    fun stop() {
        // Cancelling the session withdraws every waiting connection.
        session?.cancel()
        session = null
        lastHeard.clear()
        if (_state.value.phase != Phase.Idle) _state.value = UiState(phase = Phase.Idle)
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
            for (t in _state.value.transmitters) {
                if (t.status == TransmitterStatus.WaitingToConnect && watched.add(t.id)) launch { watch(t.id) }
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
        if (known == null) {
            val status = if (strongEnough) TransmitterStatus.WaitingToConnect else TransmitterStatus.TooFaint
            _state.update {
                it.copy(transmitters = it.transmitters + Transmitter(ad.id, ad.name, status, now, ad.rssi, wakeStartedAt = now))
            }
            log("${ad.name ?: ad.id} seen (${ad.rssi} dBm)" + if (strongEnough) "" else ": too faint to connect")
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
            )
        }
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

        while (true) {
            delay(settings.reconnectCooldownMs) // don't count the same wake-up twice
            val conn = connectWhenAwake(id)
            val at = clock.now()
            withContext(NonCancellable) { conn.close() }
            recordConnection(id, at, TransmitterStatus.CantBeChecked)
            log("${label(id)} accepted a connection")
        }
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
