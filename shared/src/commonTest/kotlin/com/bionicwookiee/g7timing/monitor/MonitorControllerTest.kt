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
import com.bionicwookiee.g7timing.ble.GattNotification
import com.bionicwookiee.g7timing.g7.G7Protocol
import com.bionicwookiee.g7timing.g7.codecheck.G7CodeCheck
import com.bionicwookiee.g7timing.g7.codecheck.G7Jpake
import com.bionicwookiee.g7timing.g7.codecheck.JpakeRound
import com.bionicwookiee.g7timing.g7.codecheck.SimulatedSensor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
    /** Made-up serial number; its check value is advertised as a real G7 does. */
    val serial: String? = null,
    /** Its 4-digit sensor code, for the code exchange. */
    val code: String = "9999",
    /** If true, it never answers the challenge (a code check gets no answer). */
    val silentOnChallenge: Boolean = false,
) {
    val dexcomData: ByteArray? = serial?.let { G7Protocol.serialCrc(it) }?.let { byteArrayOf(it.toByte(), (it shr 8).toByte(), 0, 4) }
}

/** A connection to a simulated G7, which runs its side of the code exchange like a real one. */
private class FakeConnection(
    override val id: String,
    private val answers: Boolean,
    private val canSort: Boolean,
    private val device: FakeDevice,
    private val log: MutableList<String>,
) : GattConnection {
    var discovered = false
    private val channel = Channel<GattNotification>(Channel.UNLIMITED)
    override val notifications: ReceiveChannel<GattNotification> = channel
    private val sensor = SimulatedSensor(device.code, seed = device.id.hashCode() and 0x7F)
    private val clientRounds = mutableListOf<ByteArray>()
    private var collecting = ByteArray(0)

    override suspend fun discoverServices(): List<String> {
        if (!canSort) throw CheckUnavailableException("could not clear Android's service cache")
        if (!answers) {
            delay(9_000)
            throw BleException("disconnected (status 8)")
        }
        delay(1_000)
        discovered = true
        return listOf(G7CodeCheck.SERVICE)
    }

    override suspend fun enableNotifications(service: String, characteristic: String) {
        if (!discovered) throw BleException("not discovered")
        delay(50)
    }

    private fun sendCert(bytes: ByteArray) {
        bytes.toList().chunked(20).forEach { channel.trySend(GattNotification(G7CodeCheck.CERTIFICATE, it.toByteArray())) }
    }

    override suspend fun write(service: String, characteristic: String, value: ByteArray, withResponse: Boolean) {
        delay(if (withResponse) 30 else 5)
        if (characteristic == G7CodeCheck.CERTIFICATE) {
            collecting += value
            if (collecting.size == JpakeRound.SIZE) { clientRounds += collecting; collecting = ByteArray(0) }
            return
        }
        when (value[0].toInt()) {
            0x0A -> {
                channel.trySend(GattNotification(G7CodeCheck.AUTHENTICATION, byteArrayOf(0x0A, value[1])))
                when (value[1].toInt()) {
                    0 -> sendCert(sensor.round1.encode())
                    1 -> sendCert(sensor.round2.encode())
                    2 -> sendCert(sensor.round3(JpakeRound.decode(clientRounds[0])!!, JpakeRound.decode(clientRounds[1])!!).encode())
                }
            }
            0x02 -> {
                log += "$id challenged"
                if (device.silentOnChallenge) return
                val key = sensor.sessionKey(JpakeRound.decode(clientRounds[1])!!, JpakeRound.decode(clientRounds[2])!!)
                val answer = G7Jpake.challengeAnswer(key, value.copyOfRange(1, 9))
                channel.trySend(GattNotification(G7CodeCheck.AUTHENTICATION, byteArrayOf(0x03) + answer + ByteArray(8) { 7 }))
            }
            else -> log += "$id got opcode ${value[0]}"
        }
    }

    override suspend fun close() {
        channel.close()
    }
}

/** Simulated transmitters advertising on a schedule. */
private class FakeCentral(private val devices: List<FakeDevice>) : BleCentral {
    override val availability: StateFlow<BleAvailability> = MutableStateFlow(BleAvailability.Ready)
    override val identifierLabel = "Bluetooth address"
    private val adverts = MutableSharedFlow<BleAdvertisement>(extraBufferCapacity = 16)
    /** Connections actually made, in order. */
    val connects = mutableListOf<String>()
    var scansStarted = 0
    /** Devices that answer from now on, e.g. a sensor inserted (or no longer busy) after it was first checked. */
    val nowAnswering = mutableSetOf<String>()
    /** What the simulated sensors were sent during code checks. */
    val sensorLog = mutableListOf<String>()

    override fun scan(): Flow<BleAdvertisement> = adverts.onStart { scansStarted++ }

    /** A waiting connection (timeoutMs = null) completes when the device next wakes; an immediate one succeeds at once. */
    override suspend fun connect(id: String, timeoutMs: Long?): GattConnection {
        if (timeoutMs == null) adverts.first { it.id == id }
        connects += id
        return devices.single { it.id == id }.let { FakeConnection(id, it.answers || id in nowAnswering, it.canSort, it, sensorLog) }
    }

    suspend fun advertiseForever() {
        var tick = 0
        while (true) {
            devices.filter { tick % it.everySec == 0 }
                .forEach { adverts.emit(BleAdvertisement(it.id, it.name, it.rssi, emptyList(), it.dexcomData)) }
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

    /** Synthetic GS1 element string for an applicator: (01) product, (21) serial, (17) expiry, (240) pairing code. */
    private fun applicatorCode(serial: String, pairing: String) = "0100386270004512" + "21" + serial + "\u001D" + "17280101" + "240" + pairing

    @Test
    fun scannedApplicatorIsMatchedToTheTransmitterAdvertisingItsSerial() = runTest {
        val (_, controller) = setUp(
            FakeDevice(UNWORN, "DXCMaa", -90, everySec = 60, answers = false, serial = "100000000001"),
            FakeDevice(UNWORN_2, "DXCMbb", -90, everySec = 60, answers = false, serial = "100000000002"),
        )
        // Scanned before Start: kept when the session starts.
        assertTrue(controller.onApplicatorScanned(applicatorCode("100000000002", "1234")))
        controller.start()
        advanceTimeBy(2.minutes)

        val st = controller.state.value
        assertEquals("sensor …0002", st.applicator?.label)
        assertEquals("0002", st.applicator?.serialEnding)
        assertEquals("1000 0000 0002", st.applicator?.serialGrouped, "shown in full for checking")
        assertFalse(st.applicator!!.typed, "read from the code, so no mistyping warning")
        assertFalse("100000000002" in st.applicator.toString(), "kept out of printed state")
        val matched = st.transmitters.filter { st.isTarget(it) }.map { it.name }
        assertEquals(listOf("DXCMbb"), matched)
        assertTrue(st.log.any { it.endsWith("DXCMbb is sensor …0002") }, st.log.joinToString("\n"))
        assertFalse(st.log.any { "100000000002" in it }, "the full serial is never logged")
    }

    @Test
    fun scanningDuringASessionMatchesAnAlreadyHeardTransmitter() = runTest {
        val (_, controller) = setUp(FakeDevice(UNWORN, "DXCMaa", -90, everySec = 60, answers = false, serial = "100000000001"))
        controller.start()
        advanceTimeBy(2.minutes)
        assertTrue(controller.onApplicatorScanned(applicatorCode("100000000001", "5678")))
        val st = controller.state.value
        assertTrue(st.isTarget(st.transmitters.single()))
        assertTrue(st.log.any { it.endsWith("DXCMaa is sensor …0001") })
    }

    @Test
    fun typedSerialIsMatchedLikeAScannedOne() = runTest {
        val (_, controller) = setUp(FakeDevice(UNWORN, "DXCMaa", -90, everySec = 60, answers = false, serial = "100000000001"))
        controller.start()
        advanceTimeBy(2.minutes)
        assertFalse(controller.onSerialEntered("12"))
        assertTrue(controller.state.value.applicatorError != null)
        assertTrue(controller.onSerialEntered("1000 0000 0001"))
        assertTrue(controller.state.value.applicator!!.typed)
        val st = controller.state.value
        assertEquals(null, st.applicatorError)
        assertTrue(st.isTarget(st.transmitters.single()))
        assertTrue(st.log.any { it.endsWith("Entered sensor …0001") })
        assertTrue(st.log.any { it.endsWith("DXCMaa is sensor …0001") })
        assertFalse(st.log.any { "100000000001" in it }, "the full serial is never logged")
    }

    @Test
    fun aCodeWithoutASerialIsRejected() = runTest {
        val (_, controller) = setUp()
        assertFalse(controller.onApplicatorScanned("https://example.com"))
        assertEquals(null, controller.state.value.applicator)
        assertTrue(controller.state.value.applicatorError != null)
    }

    @Test
    fun scannedTransmitterThatDidntRespondIsCheckedAgainAndOthersAreNot() = runTest {
        val (central, controller) = setUp(
            FakeDevice(UNWORN, "DXCMaa", -90, everySec = 60, answers = false, serial = "100000000001"),
            FakeDevice(UNWORN_2, "DXCMbb", -90, everySec = 60, answers = false, serial = "100000000002"),
        )
        controller.onApplicatorScanned(applicatorCode("100000000001", "1234"))
        controller.start()
        advanceTimeBy(2.minutes)
        assertTrue(controller.state.value.transmitters.all { it.status == TransmitterStatus.CantBeChecked })

        // Both start answering (say, they were busy before); only the scanned one is asked again.
        central.nowAnswering += listOf(UNWORN, UNWORN_2)
        advanceTimeBy(2.minutes)
        val st = controller.state.value
        assertEquals(TransmitterStatus.Working, st.transmitters.single { it.id == UNWORN }.status, st.log.joinToString("\n"))
        assertEquals(TransmitterStatus.CantBeChecked, st.transmitters.single { it.id == UNWORN_2 }.status)
        assertTrue(st.log.any { it.endsWith("DXCMaa responded when checked again: working sensor, left alone") })

        // Left alone from then on.
        val connections = central.connects.count { it == UNWORN }
        advanceTimeBy(5.minutes)
        assertEquals(connections, central.connects.count { it == UNWORN })
    }

    @Test
    fun scannedTransmitterIsReCheckedOnlyAFewTimes() = runTest {
        val (_, controller) = setUp(FakeDevice(UNWORN, "DXCMaa", -90, everySec = 60, answers = false, serial = "100000000001"))
        controller.onApplicatorScanned(applicatorCode("100000000001", "1234"))
        controller.start()
        advanceTimeBy(10.minutes)
        val log = controller.state.value.log
        assertEquals(3, log.count { "but didn't respond (check" in it }, log.joinToString("\n"))
        assertEquals(TransmitterStatus.CantBeChecked, controller.state.value.transmitters.single().status)
    }

    // ------------------------------------------------------------------ sensor code

    @Test
    fun sensorCodeIsCheckedOnEachRespondingSensorAndNeverSignsIn() = runTest {
        val (central, controller) = setUp(
            FakeDevice(WORKING, "DXCMaa", -80, everySec = 300, answers = true, code = "1234"),
            FakeDevice(UNWORN_2, "DXCMbb", -80, everySec = 300, answers = true, code = "5678"),
            FakeDevice(UNWORN, "DXCMcc", -90, everySec = 60, answers = false, code = "1234"),
        )
        assertTrue(controller.onSensorCodeEntered("1234"))
        controller.start()
        advanceTimeBy(6.minutes)

        val st = controller.state.value
        val byName = st.transmitters.associateBy { it.name }
        assertEquals(CodeStatus.Matches, byName["DXCMaa"]!!.codeStatus, st.log.joinToString("\n"))
        assertEquals(CodeStatus.DoesNotMatch, byName["DXCMbb"]!!.codeStatus)
        assertEquals(1, byName["DXCMaa"]!!.codeAttempts, "checked on the connection that found it working")
        // The unworn one doesn't respond, so its code can't be checked.
        assertEquals(CodeStatus.NotChecked, byName["DXCMcc"]!!.codeStatus)
        assertEquals(listOf("DXCMaa"), st.transmitters.filter { st.isTarget(it) }.map { it.name })
        assertTrue(st.log.any { it.endsWith("DXCMaa uses the sensor code") })
        assertTrue(st.log.any { it.endsWith("DXCMbb doesn't use the sensor code") })
        assertFalse(central.sensorLog.any { "opcode" in it }, "never answers the sensor's challenge: ${central.sensorLog}")
        assertFalse(st.log.any { "1234" in it }, "the code is never logged")
        assertFalse("1234" in st.toString(), "kept out of printed state")
        // Each working sensor was connected to once: the check happened on that connection.
        assertEquals(1, central.connects.count { it == WORKING })
    }

    @Test
    fun searchCarriesOnAfterAMatch() = runTest {
        val (_, controller) = setUp(
            FakeDevice(WORKING, "DXCMaa", -80, everySec = 300, answers = true, code = "1234"),
            FakeDevice(UNWORN_2, "DXCMbb", -80, everySec = 300, answers = true, code = "1234"),
        )
        controller.onSensorCodeEntered("1234")
        controller.start()
        advanceTimeBy(6.minutes)
        val st = controller.state.value
        assertEquals(listOf("DXCMaa", "DXCMbb"), st.transmitters.filter { st.isTarget(it) }.mapNotNull { it.name }.sorted())
    }

    @Test
    fun codeEnteredDuringASessionChecksWorkingSensorsOnTheirNextWake() = runTest {
        val (central, controller) = setUp(FakeDevice(WORKING, "DXCMaa", -80, everySec = 300, answers = true, code = "1234"))
        controller.start()
        advanceTimeBy(6.minutes)
        assertEquals(TransmitterStatus.Working, controller.state.value.transmitters.single().status)
        val before = central.connects.count { it == WORKING }
        controller.onSensorCodeEntered("1234")
        advanceTimeBy(6.minutes)
        val t = controller.state.value.transmitters.single()
        assertEquals(CodeStatus.Matches, t.codeStatus, controller.state.value.log.joinToString("\n"))
        assertEquals(1, t.codeAttempts)
        assertTrue(central.connects.count { it == WORKING } > before, "connected again to check it")
    }

    @Test
    fun noAnswerIsTriedTwiceThenGivenUp() = runTest {
        val (central, controller) = setUp(
            FakeDevice(WORKING, "DXCMaa", -80, everySec = 60, answers = true, code = "1234", silentOnChallenge = true),
        )
        controller.onSensorCodeEntered("1234")
        controller.start()
        advanceTimeBy(10.minutes)
        val t = controller.state.value.transmitters.single()
        assertEquals(CodeStatus.GaveUp, t.codeStatus, controller.state.value.log.joinToString("\n"))
        assertEquals(2, t.codeAttempts)
        assertEquals(2, central.sensorLog.count { it.endsWith("challenged") })
        val connections = central.connects.size
        advanceTimeBy(10.minutes)
        assertEquals(connections, central.connects.size, "no more connections after the second try")
    }

    @Test
    fun codeAndSerialReplaceEachOther() = runTest {
        val (_, controller) = setUp()
        assertFalse(controller.onSensorCodeEntered("123"))
        assertFalse(controller.onSensorCodeEntered("12a4"))
        assertTrue(controller.onSerialEntered("100000000001"))
        assertTrue(controller.onSensorCodeEntered("12 34"))
        assertEquals(null, controller.state.value.applicator, "a code replaces the serial number")
        assertEquals("1234", controller.state.value.sensorCode)
        assertTrue(controller.onSerialEntered("100000000001"))
        assertEquals(null, controller.state.value.sensorCode, "a serial number replaces the code")
        controller.onSensorCodeEntered("1234")
        controller.clearApplicator()
        assertEquals(null, controller.state.value.sensorCode)
    }
}
