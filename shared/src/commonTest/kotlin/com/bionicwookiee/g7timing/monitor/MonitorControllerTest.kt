// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.monitor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import com.bionicwookiee.g7timing.ble.BleAdvertisement
import com.bionicwookiee.g7timing.ble.BleAvailability
import com.bionicwookiee.g7timing.ble.BleCentral
import com.bionicwookiee.g7timing.ble.BleException
import com.bionicwookiee.g7timing.ble.CheckUnavailableException
import com.bionicwookiee.g7timing.ble.GattConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val UNWORN = "CE:92:00:00:6C:05"
private const val UNWORN_2 = "FE:AF:00:00:DB:38"
private const val WORKING = "D7:1A:00:00:95:25"
private const val G6 = "FB:5C:00:00:78:43"

/**
 * [everySec]: how often the device wakes and advertises. [answers]: whether it lists its services
 * after connecting (a working sensor does; an unworn one in its applicator doesn't).
 */
private data class FakeDevice(
    val id: String, val name: String, val rssi: Int, val everySec: Int, val answers: Boolean, val canSort: Boolean = true,
)

private class FakeConnection(override val id: String, private val answers: Boolean, private val canSort: Boolean) : GattConnection {
    var discovered = false
    override suspend fun discoverServices(): List<String> {
        if (!canSort) throw CheckUnavailableException("could not clear Android's service cache")
        if (!answers) {
            delay(9_000)
            throw BleException("disconnected (status 8)")
        }
        delay(1_000)
        discovered = true
        return listOf("F8083532-849E-531C-C594-30F1F86A4EA5")
    }
    override suspend fun close() {}
}

/** Simulated transmitters advertising on a schedule. */
private class FakeCentral(private val devices: List<FakeDevice>) : BleCentral {
    override val availability: StateFlow<BleAvailability> = MutableStateFlow(BleAvailability.Ready)
    override val identifierLabel = "Bluetooth address"
    private val adverts = MutableSharedFlow<BleAdvertisement>(extraBufferCapacity = 16)
    /** Connections actually made, in order. */
    val connects = mutableListOf<String>()
    var scansStarted = 0

    override fun scan(): Flow<BleAdvertisement> = adverts.onStart { scansStarted++ }

    /** A waiting connection (timeoutMs = null) completes when the device next wakes; an immediate one succeeds at once. */
    override suspend fun connect(id: String, timeoutMs: Long?): GattConnection {
        if (timeoutMs == null) adverts.first { it.id == id }
        connects += id
        return devices.single { it.id == id }.let { FakeConnection(id, it.answers, it.canSort) }
    }

    suspend fun advertiseForever() {
        var tick = 0
        while (true) {
            devices.filter { tick % it.everySec == 0 }
                .forEach { adverts.emit(BleAdvertisement(it.id, it.name, it.rssi, emptyList())) }
            delay(1.seconds)
            tick++
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.virtualClock() = object : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(1_800_000_000_000 + testScheduler.currentTime)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorControllerTest {
    private fun TestScope.setUp(vararg devices: FakeDevice): Pair<FakeCentral, MonitorController> {
        val central = FakeCentral(devices.toList())
        val controller = MonitorController(central, backgroundScope, clock = virtualClock())
        backgroundScope.launch { central.advertiseForever() }
        return central to controller
    }

    @Test
    fun unwornTransmitterIsShownWithEveryWakeAndItsSignal() = runTest {
        val (_, controller) = setUp(FakeDevice(UNWORN, "DX0299", -88, everySec = 60, answers = false))
        controller.start()
        advanceTimeBy(10.minutes + 30.seconds)

        val t = controller.state.value.transmitters.single()
        assertEquals("DX0299", t.name)
        assertEquals(TransmitterStatus.CantBeChecked, t.status)
        assertEquals(-88, t.lastSeenRssi)
        // First heard at 1:00 (the scan starts just after the 0:00 wake), then every wake to 10:00.
        assertEquals(10, t.connectionCount, controller.state.value.log.joinToString("\n"))
        val sinceLastSeen = virtualClock().now() - t.lastSeenAt
        assertTrue(sinceLastSeen < 31.seconds, "last seen at the 10:00 wake-up, $sinceLastSeen ago")
        assertEquals(60.seconds, t.lastInterval, "time between the last two connections")
    }

    @Test
    fun lastIntervalNeedsTwoConnections() = runTest {
        val (_, controller) = setUp(FakeDevice(UNWORN, "DX0299", -88, everySec = 120, answers = false))
        controller.start()
        advanceTimeBy(2.minutes + 30.seconds)
        val first = controller.state.value.transmitters.single()
        assertEquals(1, first.connectionCount)
        assertEquals(null, first.lastInterval)

        advanceTimeBy(2.minutes)
        // The first connection can land up to 0.5 s after the wake-up (the search loop's polling step).
        val interval = controller.state.value.transmitters.single().lastInterval!!
        assertTrue(interval in (2.minutes - 1.seconds)..2.minutes, "interval $interval")
    }

    @Test
    fun workingSensorIsConnectedOnceThenLeftAloneButStillShown() = runTest {
        val (central, controller) = setUp(FakeDevice(WORKING, "DXCMUP", -85, everySec = 300, answers = true))
        controller.start()
        // 31 minutes so the last two wake-ups (25:00, 30:00) are clear of the fake's scan restart at 20:00.
        advanceTimeBy(31.minutes)

        assertEquals(listOf(WORKING), central.connects, "one connection to sort it, then never again")
        val t = controller.state.value.transmitters.single()
        assertEquals(TransmitterStatus.Working, t.status)
        assertEquals(1, t.connectionCount)
        val sinceLastSeen = virtualClock().now() - t.lastSeenAt
        assertTrue(sinceLastSeen <= 5.minutes, "still seen by the scan: last seen $sinceLastSeen ago")
        assertEquals(-85, t.lastSeenRssi)
        assertEquals(5.minutes, t.lastWakeInterval, "interval between wake-ups heard by the scan, without reconnecting")
    }

    @Test
    fun aBurstOfAdvertisementsIsOneWakeUp() = runTest {
        // Advertises every second: one continuous burst, so no second wake-up and no interval.
        val (_, controller) = setUp(FakeDevice(WORKING, "DXCMUP", -85, everySec = 1, answers = true))
        controller.start()
        advanceTimeBy(3.minutes)
        assertEquals(null, controller.state.value.transmitters.single().lastWakeInterval)
    }

    @Test
    fun aTransmitterThatCantBeSortedIsLeftAlone() = runTest {
        val (central, controller) = setUp(FakeDevice(WORKING, "DXCMUP", -85, everySec = 60, answers = true, canSort = false))
        controller.start()
        advanceTimeBy(10.minutes)
        assertEquals(listOf(WORKING), central.connects)
        assertEquals(TransmitterStatus.CouldNotSort, controller.state.value.transmitters.single().status)
    }

    @Test
    fun g6TransmittersAreNeverContacted() = runTest {
        val (central, controller) = setUp(FakeDevice(G6, "DexcomK1", -50, everySec = 1, answers = true))
        controller.start()
        advanceTimeBy(5.minutes)
        assertTrue(central.connects.isEmpty(), "connected to a G6: ${central.connects}")
        assertTrue(controller.state.value.transmitters.isEmpty())
    }

    @Test
    fun transmittersBelowTheSignalFloorAreNotContacted() = runTest {
        val (central, controller) = setUp(
            FakeDevice(UNWORN, "DX0299", -98, everySec = 60, answers = false),
            FakeDevice(UNWORN_2, "DXCMQ4", -100, everySec = 60, answers = false),
        )
        controller.start()
        advanceTimeBy(5.minutes)
        val byId = controller.state.value.transmitters.associateBy { it.id }
        assertEquals(TransmitterStatus.CantBeChecked, byId.getValue(UNWORN).status)
        assertEquals(TransmitterStatus.TooFaint, byId.getValue(UNWORN_2).status, "shown, but not contacted")
        assertEquals(-100, byId.getValue(UNWORN_2).lastSeenRssi)
        assertFalse(UNWORN_2 in central.connects)
    }

    @Test
    fun twoUnwornTransmittersAreBothShownAlongsideAWorkingOne() = runTest {
        val (_, controller) = setUp(
            FakeDevice(UNWORN, "DX0299", -88, everySec = 60, answers = false),
            FakeDevice(UNWORN_2, "DXCMQ4", -94, everySec = 120, answers = false),
            FakeDevice(WORKING, "DXCMUP", -82, everySec = 300, answers = true),
        )
        controller.start()
        advanceTimeBy(12.minutes)

        val byId = controller.state.value.transmitters.associateBy { it.id }
        assertEquals(TransmitterStatus.CantBeChecked, byId.getValue(UNWORN).status)
        assertEquals(TransmitterStatus.CantBeChecked, byId.getValue(UNWORN_2).status)
        assertEquals(TransmitterStatus.Working, byId.getValue(WORKING).status)
        assertTrue(byId.getValue(UNWORN).connectionCount > 1 && byId.getValue(UNWORN_2).connectionCount > 1)
        assertEquals(1, byId.getValue(WORKING).connectionCount)
    }

    @Test
    fun startTimeIsKeptAndEachStartBeginsFromScratch() = runTest {
        val (central, controller) = setUp(
            FakeDevice(UNWORN, "DX0299", -88, everySec = 60, answers = false),
            FakeDevice(WORKING, "DXCMUP", -82, everySec = 60, answers = true),
        )
        val first = virtualClock().now()
        controller.start()
        assertEquals(first, controller.state.value.startedAt)
        advanceTimeBy(3.minutes)
        assertEquals(first, controller.state.value.startedAt)
        assertEquals(2, controller.state.value.transmitters.size)

        controller.stop()
        assertEquals(UiState(), controller.state.value)

        central.connects.clear()
        controller.start()
        assertTrue(controller.state.value.transmitters.isEmpty())
        advanceTimeBy(3.minutes)
        assertTrue(WORKING in central.connects, "a new session sorts the working sensor again")
        assertEquals(
            mapOf(UNWORN to TransmitterStatus.CantBeChecked, WORKING to TransmitterStatus.Working),
            controller.state.value.transmitters.associate { it.id to it.status },
        )
    }

    @Test
    fun scanIsRestartedBeforeAndroidThrottlesIt() = runTest {
        val (central, controller) = setUp(FakeDevice(UNWORN, "DX0299", -88, everySec = 60, answers = false))
        controller.start()
        advanceTimeBy(13.minutes)
        assertTrue(central.scansStarted >= 4, "scan started ${central.scansStarted} times in 13 minutes")
    }
}
