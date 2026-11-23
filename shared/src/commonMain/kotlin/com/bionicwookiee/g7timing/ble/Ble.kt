// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.ble

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class BleAvailability { Unknown, Ready, PoweredOff, Unauthorized, Unsupported }

data class BleAdvertisement(
    /** Android: Bluetooth MAC address. iOS: CoreBluetooth peripheral identifier (per-phone UUID). */
    val id: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    /** Dexcom manufacturer-specific data (company 0x00D0), without the company ID; null if absent. */
    val dexcomData: ByteArray? = null,
)

class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The phone can't make the device answer for itself (e.g. its service cache can't be cleared), so it can't be sorted. */
class CheckUnavailableException(message: String) : Exception(message)

/** A value a device sent by notification or indication. */
class GattNotification(val characteristic: String, val value: ByteArray)

/** Platform Bluetooth LE central. Implementations never create a bond. */
interface BleCentral {
    val availability: StateFlow<BleAvailability>

    /** What the platform calls the device identifier we can see (MAC on Android, UUID on iOS). */
    val identifierLabel: String

    /** Cold flow of advertisements; scanning stops when collection is cancelled. */
    fun scan(): Flow<BleAdvertisement>

    /**
     * Connects (link layer only). Throws [BleException] on failure or timeout.
     *
     * With [timeoutMs] = null this is a *waiting* connection: the Bluetooth controller connects
     * the instant the device next advertises, however briefly, and the call suspends until then.
     * Sleeping G7s wake only for a moment, so this catches wake-ups a scan-then-connect misses.
     * Cancelling the call withdraws the request.
     */
    suspend fun connect(id: String, timeoutMs: Long? = null): GattConnection
}

interface GattConnection {
    val id: String

    /**
     * Asks the device to list its GATT services. A working G7 answers within a few seconds; an
     * unworn one accepts the connection and then answers nothing, so this throws [BleException]
     * (timeout or link lost). The answer must come from the device, not from a cache: throws
     * [CheckUnavailableException] if that can't be guaranteed.
     */
    suspend fun discoverServices(): List<String>

    /**
     * Values the device sends from characteristics subscribed with [enableNotifications], in the
     * order they arrive. Nothing is dropped, so values that arrive before anyone reads are kept.
     */
    val notifications: ReceiveChannel<GattNotification>

    /**
     * Subscribes to notifications (or indications) from a characteristic. Call after
     * [discoverServices]. Throws [BleException] if it isn't there or the device refuses.
     */
    suspend fun enableNotifications(service: String, characteristic: String)

    /**
     * Writes to a characteristic. With [withResponse], waits for the device to acknowledge.
     * Throws [BleException] on failure. Never asks for a bond: if the device demands encryption,
     * the write fails.
     */
    suspend fun write(service: String, characteristic: String, value: ByteArray, withResponse: Boolean)

    /** Disconnects and releases the connection. Safe to call more than once. */
    suspend fun close()
}

/** Normalises a UUID string for comparison across platforms. */
fun normUuid(uuid: String): String = uuid.uppercase()
