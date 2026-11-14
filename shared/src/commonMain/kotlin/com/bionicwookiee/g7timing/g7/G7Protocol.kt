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
}
