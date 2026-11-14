// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.bionicwookiee.g7timing.ble

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/**
 * iOS BLE central on CoreBluetooth. Callbacks arrive on the main queue.
 *
 * iOS never exposes a peripheral's Bluetooth MAC address; the identifier is a UUID that
 * CoreBluetooth assigns per phone. No bonding is requested: iOS only pairs if a characteristic
 * demands encryption, and G7timing never reads or writes a characteristic.
 */
class IosBleCentral : BleCentral {
    private val _availability = MutableStateFlow(BleAvailability.Unknown)
    override val availability: StateFlow<BleAvailability> = _availability.asStateFlow()
    override val identifierLabel = "Bluetooth identifier (iOS)"

    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val connections = mutableMapOf<String, IosGattConnection>()
    private var scanListener: ((BleAdvertisement) -> Unit)? = null

    private val delegate = CentralDelegate()
    private val manager = CBCentralManager(delegate = delegate, queue = null)

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            _availability.value = when (central.state) {
                CBManagerStatePoweredOn -> BleAvailability.Ready
                CBManagerStatePoweredOff -> BleAvailability.PoweredOff
                CBManagerStateUnauthorized -> BleAvailability.Unauthorized
                CBManagerStateUnsupported -> BleAvailability.Unsupported
                else -> BleAvailability.Unknown
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            peripherals[id] = didDiscoverPeripheral
            val name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?: didDiscoverPeripheral.name
            val services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
                ?.mapNotNull { (it as? CBUUID)?.UUIDString?.let(::normUuid) }
                .orEmpty()
            scanListener?.invoke(BleAdvertisement(id, name, RSSI.intValue, services))
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            connections[didConnectPeripheral.identifier.UUIDString]?.onConnected()
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            connections.remove(didFailToConnectPeripheral.identifier.UUIDString)
                ?.onDisconnected("connect failed: ${error?.localizedDescription}")
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            connections.remove(didDisconnectPeripheral.identifier.UUIDString)
                ?.onDisconnected("disconnected${error?.let { ": " + it.localizedDescription } ?: ""}")
        }
    }

    override fun scan(): Flow<BleAdvertisement> = callbackFlow {
        availability.first { it == BleAvailability.Ready }
        scanListener = { trySend(it) }
        manager.scanForPeripheralsWithServices(
            serviceUUIDs = null,
            options = mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
        awaitClose {
            scanListener = null
            manager.stopScan()
        }
    }

    override suspend fun connect(id: String, timeoutMs: Long?): GattConnection {
        val peripheral = peripherals[id]
            ?: NSUUID(uUIDString = id).let { uuid ->
                manager.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull() as? CBPeripheral
            }
            ?: throw BleException("unknown peripheral")
        val conn = IosGattConnection(peripheral) { manager.cancelPeripheralConnection(peripheral) }
        connections[id] = conn
        // CoreBluetooth connection requests never expire: with timeoutMs = null this waits for the
        // sensor's next wake-up.
        manager.connectPeripheral(peripheral, options = null)
        try {
            if (timeoutMs == null) conn.awaitConnected() else withTimeout(timeoutMs) { conn.awaitConnected() }
        } catch (e: TimeoutCancellationException) {
            connections.remove(id)
            manager.cancelPeripheralConnection(peripheral)
            throw BleException("timed out")
        } catch (e: Throwable) {
            // Includes cancellation: withdraw the pending request.
            connections.remove(id)
            manager.cancelPeripheralConnection(peripheral)
            throw e
        }
        return conn
    }
}

private class IosGattConnection(
    private val peripheral: CBPeripheral,
    private val cancel: () -> Unit,
) : GattConnection {
    override val id: String = peripheral.identifier.UUIDString

    private val connected = CompletableDeferred<Unit>()
    private var discovery: CompletableDeferred<Unit>? = null
    private var closed = false

    private val delegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val d = discovery ?: return
            if (didDiscoverServices == null) d.complete(Unit)
            else d.completeExceptionally(BleException(didDiscoverServices.localizedDescription))
        }
    }

    init {
        peripheral.delegate = delegate
    }

    fun onConnected() {
        connected.complete(Unit)
    }

    fun onDisconnected(reason: String) {
        val e = BleException(reason)
        connected.completeExceptionally(e)
        discovery?.completeExceptionally(e)
        closed = true
    }

    suspend fun awaitConnected() = connected.await()

    override suspend fun discoverServices(): List<String> {
        if (closed) throw BleException("connection closed")
        val d = CompletableDeferred<Unit>()
        discovery = d
        try {
            peripheral.discoverServices(null)
            withTimeout(DISCOVERY_TIMEOUT_MS) { d.await() }
            return peripheral.services.orEmpty().mapNotNull { (it as? CBService)?.UUID?.UUIDString?.let(::normUuid) }
        } catch (e: TimeoutCancellationException) {
            throw BleException("service discovery timed out")
        } finally {
            discovery = null
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        discovery?.completeExceptionally(BleException("closed"))
        cancel()
    }
}

/** How long a sensor gets to list its services. A working G7 took up to about 2.5 s. */
private const val DISCOVERY_TIMEOUT_MS = 10_000L
