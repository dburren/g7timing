// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.bionicwookiee.g7timing.ble

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
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
 * demands encryption. G7timing writes only during a sensor-code check, to characteristics a G7
 * accepts without encryption, and stops before the sensor would ask for a bond.
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
            val dexcomData = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData)?.dexcomPayload()
            scanListener?.invoke(BleAdvertisement(id, name, RSSI.intValue, services, dexcomData))
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
    private var characteristicDiscovery: CompletableDeferred<Unit>? = null
    private var pendingOp: CompletableDeferred<Unit>? = null
    private var closed = false
    private val opLock = Mutex()
    private val _notifications = Channel<GattNotification>(Channel.UNLIMITED)
    override val notifications: ReceiveChannel<GattNotification> = _notifications

    private fun finish(d: CompletableDeferred<Unit>?, error: NSError?) {
        if (d == null) return
        if (error == null) d.complete(Unit) else d.completeExceptionally(BleException(error.localizedDescription))
    }

    private val delegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            finish(discovery, didDiscoverServices)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            finish(characteristicDiscovery, error)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            finish(pendingOp, error)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            finish(pendingOp, error)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error != null) return
            val value = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            _notifications.trySend(GattNotification(normUuid(didUpdateValueForCharacteristic.UUID.UUIDString), value))
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
        characteristicDiscovery?.completeExceptionally(e)
        pendingOp?.completeExceptionally(e)
        _notifications.close()
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

    /** Finds a characteristic, discovering the service's characteristics the first time. */
    private suspend fun characteristic(service: String, characteristic: String): CBCharacteristic {
        if (closed) throw BleException("connection closed")
        val svc = peripheral.services.orEmpty().firstOrNull { (it as? CBService)?.UUID?.UUIDString?.let(::normUuid) == normUuid(service) } as? CBService
            ?: throw BleException("service ${service.take(8)} not found")
        fun find() = svc.characteristics.orEmpty().firstOrNull {
            (it as? CBCharacteristic)?.UUID?.UUIDString?.let(::normUuid) == normUuid(characteristic)
        } as? CBCharacteristic
        find()?.let { return it }
        val d = CompletableDeferred<Unit>()
        characteristicDiscovery = d
        try {
            peripheral.discoverCharacteristics(null, svc)
            withTimeout(OP_TIMEOUT_MS) { d.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException("characteristic discovery timed out")
        } finally {
            characteristicDiscovery = null
        }
        return find() ?: throw BleException("characteristic ${characteristic.take(8)} not found")
    }

    private suspend fun op(name: String, start: () -> Unit) = opLock.withLock {
        if (closed) throw BleException("connection closed")
        val d = CompletableDeferred<Unit>()
        pendingOp = d
        try {
            start()
            withTimeout(OP_TIMEOUT_MS) { d.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException("$name timed out")
        } finally {
            pendingOp = null
        }
    }

    override suspend fun enableNotifications(service: String, characteristic: String) {
        val ch = characteristic(service, characteristic)
        op("subscribing") { peripheral.setNotifyValue(true, ch) }
    }

    override suspend fun write(service: String, characteristic: String, value: ByteArray, withResponse: Boolean) {
        val ch = characteristic(service, characteristic)
        val data = value.toNSData()
        if (withResponse) {
            op("write") { peripheral.writeValue(data, ch, CBCharacteristicWriteWithResponse) }
        } else {
            opLock.withLock {
                if (closed) throw BleException("connection closed")
                peripheral.writeValue(data, ch, CBCharacteristicWriteWithoutResponse)
            }
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        discovery?.completeExceptionally(BleException("closed"))
        characteristicDiscovery?.completeExceptionally(BleException("closed"))
        pendingOp?.completeExceptionally(BleException("closed"))
        _notifications.close()
        cancel()
    }
}

/** How long a sensor gets to list its services. A working G7 took up to about 2.5 s. */
private const val DISCOVERY_TIMEOUT_MS = 10_000L

/** How long one write, subscription or characteristic discovery may take. */
private const val OP_TIMEOUT_MS = 4_000L

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return bytes?.readBytes(n) ?: ByteArray(0)
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * CoreBluetooth gives manufacturer data with the 2-byte company ID (little-endian) in front.
 * Returns the bytes after it when the company is Dexcom (0x00D0), else null.
 */
private fun NSData.dexcomPayload(): ByteArray? {
    val n = length.toInt()
    if (n < 2) return null
    val all = bytes?.readBytes(n) ?: return null
    val company = (all[0].toInt() and 0xFF) or ((all[1].toInt() and 0xFF) shl 8)
    return if (company == 0x00D0) all.copyOfRange(2, n) else null
}
