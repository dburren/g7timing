// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7

/** How G7timing recognises a Dexcom G7 transmitter from its Bluetooth advertisement. */
object G7Protocol {
    /** 16-bit service UUID Dexcom transmitters (G6 and G7) advertise. */
    const val ADVERTISED_SERVICE_16 = "FEBC"

    /** Name prefixes a G7 advertises with, e.g. "DXCMQ4" or "DX0299". */
    val NAME_PREFIXES = listOf("DXCM", "DX01", "DX02")

    /** G5/G6 transmitters advertise as "Dexcom" + 2 characters (e.g. "DexcomK1"). Never a G7. */
    const val G6_NAME_PREFIX = "Dexcom"

    /**
     * True for a G7 name, or an unnamed device advertising the Dexcom FEBC service.
     * G6 transmitters (also FEBC, but named "Dexcom…") are always excluded.
     */
    fun looksLikeG7(name: String?, serviceUuids: List<String>): Boolean {
        if (name != null) return NAME_PREFIXES.any { name.startsWith(it) }
        return serviceUuids.any { it.uppercase().let { u -> u == ADVERTISED_SERVICE_16 || u.startsWith("0000FEBC-") } }
    }

    /** Bluetooth SIG company ID Dexcom uses in its manufacturer-specific advertisement data. */
    const val DEXCOM_COMPANY_ID = 0x00D0

    /**
     * The serial-number check value a G7 advertises: the first 2 bytes of its Dexcom
     * manufacturer data, low byte first. Null if the data is missing or too short.
     *
     * Found on hardware: these bytes are [serialCrc] of the serial number printed (and encoded)
     * on the sensor's applicator. The advertised name's suffix is unrelated to the serial.
     */
    fun advertisedSerialCrc(dexcomData: ByteArray?): Int? {
        if (dexcomData == null || dexcomData.size < 2) return null
        return (dexcomData[0].toInt() and 0xFF) or ((dexcomData[1].toInt() and 0xFF) shl 8)
    }

    /** CRC-16/XMODEM (poly 0x1021, init 0, not reflected, no final XOR) of [serial] as ASCII. */
    fun serialCrc(serial: String): Int {
        var crc = 0
        for (ch in serial) {
            crc = crc xor ((ch.code and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
