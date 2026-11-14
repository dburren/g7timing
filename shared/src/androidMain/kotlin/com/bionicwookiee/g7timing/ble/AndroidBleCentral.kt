// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val DEXCOM_SERVICE_UUID = "0000FEBC-0000-1000-8000-00805F9B34FB"
private const val DEXCOM_COMPANY_ID = 0x00D0

/**
 * Android BLE central. It only scans, connects and does GATT reads/writes. It never calls
 * createBond(), so no pairing is requested by this app.
 */
@SuppressLint("MissingPermission") // Permissions are checked before use; see hasPermissions().
class AndroidBleCentral(context: Context) : BleCentral {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val _availability = MutableStateFlow(BleAvailability.Unknown)
    override val availability: StateFlow<BleAvailability> = _availability.asStateFlow()
    override val identifierLabel = "Bluetooth address"

    init {
        refreshAvailability()
        appContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = refreshAvailability()
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    /** Call after the user responds to the permission prompt. */
    fun refreshAvailability() {
        _availability.value = when {
            adapter == null || !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ->
                BleAvailability.Unsupported
            !hasPermissions(appContext) -> BleAvailability.Unauthorized
            !adapter.isEnabled -> BleAvailability.PoweredOff
            else -> BleAvailability.Ready
        }
    }

    override fun scan(): Flow<BleAdvertisement> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth scanner unavailable")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                trySend(
                    BleAdvertisement(
                        id = result.device.address,
                        name = record?.deviceName ?: runCatching { result.device.name }.getOrNull(),
                        rssi = result.rssi,
                        serviceUuids = record?.serviceUuids?.map { normUuid(it.uuid.toString()) }.orEmpty(),
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Scan failed (error $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                // By default Android reports only legacy (Bluetooth 4) advertisements. Also accept
                // Bluetooth 5 extended advertising on every PHY, in case the sensor uses it.
                if (adapter.isLeExtendedAdvertisingSupported) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        // Android demotes long unfiltered scans to "opportunistic" (it then only passes on what other
        // apps scan for), so ask only for Dexcom devices: the FEBC service or Dexcom's company ID.
        // G6 transmitters match too; G7Protocol.looksLikeG7 drops them.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(DEXCOM_SERVICE_UUID)).build(),
            ScanFilter.Builder().setManufacturerData(DEXCOM_COMPANY_ID, ByteArray(0)).build(),
        )
        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    override suspend fun connect(id: String, timeoutMs: Long?): GattConnection {
        val device = adapter?.getRemoteDevice(id) ?: throw BleException("Bluetooth unavailable")
        // autoConnect=true hands the request to the controller, which connects on the device's
        // very next advertisement and never times out.
        val conn = AndroidGattConnection(appContext, device, autoConnect = timeoutMs == null)
        try {
            if (timeoutMs == null) conn.awaitConnected() else withTimeout(timeoutMs) { conn.awaitConnected() }
        } catch (e: TimeoutCancellationException) {
            withContext(NonCancellable) { conn.close() }
            throw BleException("timed out")
        } catch (e: Throwable) {
            // Includes cancellation: withdraw the pending request.
            withContext(NonCancellable) { conn.close() }
            throw e
        }
        return conn
    }

    companion object {
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context) = requiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@SuppressLint("MissingPermission")
private class AndroidGattConnection(context: Context, device: BluetoothDevice, autoConnect: Boolean) : GattConnection {
    override val id: String = device.address

    private val connected = CompletableDeferred<Unit>()
    @Volatile private var discovery: CompletableDeferred<Int>? = null
    @Volatile private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val err = BleException("disconnected (status $status)")
                connected.completeExceptionally(err)
                discovery?.completeExceptionally(err)
                if (!closed) { closed = true; gatt.close() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            discovery?.complete(status)
        }
    }

    // TRANSPORT_LE avoids classic-BT confusion.
    private val gatt: BluetoothGatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)

    suspend fun awaitConnected() = connected.await()

    override suspend fun discoverServices(): List<String> {
        if (closed) throw BleException("connection closed")
        // Android answers discovery from its own cache for devices it has connected to before,
        // without asking the device (a reply came 14 ms after connecting). Clear that cache so the
        // sensor itself has to answer. refresh() is hidden API, widely used for this.
        val refreshed = runCatching { gatt.javaClass.getMethod("refresh").invoke(gatt) as Boolean }.getOrDefault(false)
        if (!refreshed) throw CheckUnavailableException("could not clear Android's service cache")
        delay(REFRESH_SETTLE_MS)
        val d = CompletableDeferred<Int>()
        discovery = d
        try {
            if (!gatt.discoverServices()) throw BleException("service discovery could not be started")
            val status = withTimeout(DISCOVERY_TIMEOUT_MS) { d.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("service discovery failed (status $status)")
            return gatt.services.map { normUuid(it.uuid.toString()) }
        } catch (e: TimeoutCancellationException) {
            throw BleException("service discovery timed out")
        } finally {
            discovery = null
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        discovery?.completeExceptionally(BleException("closed"))
    }
}

/** How long a sensor gets to list its services. A working G7 took up to about 2.5 s. */
private const val DISCOVERY_TIMEOUT_MS = 10_000L

/** Pause after clearing the service cache, before starting discovery. */
private const val REFRESH_SETTLE_MS = 300L

