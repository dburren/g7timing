// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class G7ProtocolTest {
    private val febc = listOf("0000FEBC-0000-1000-8000-00805F9B34FB")

    @Test fun g7NamesAreAccepted() {
        assertTrue(G7Protocol.looksLikeG7("DXCMQ4", emptyList()))
        assertTrue(G7Protocol.looksLikeG7("DX01Q4", emptyList()))
        assertTrue(G7Protocol.looksLikeG7("DX02AB", febc))
        assertTrue(G7Protocol.looksLikeG7(null, febc), "unnamed FEBC advertiser may be a G7")
    }

    @Test fun g6TransmittersAreRejectedEvenWithFebc() {
        assertFalse(G7Protocol.looksLikeG7("DexcomK1", febc))
        assertFalse(G7Protocol.looksLikeG7("DexcomKE", febc))
    }

    @Test fun unrelatedDevicesAreRejected() {
        assertFalse(G7Protocol.looksLikeG7(null, emptyList()))
        assertFalse(G7Protocol.looksLikeG7("Govee_H5074", listOf("0000EC88-0000-1000-8000-00805F9B34FB")))
    }

    @Test fun serialCrcIsCrc16Xmodem() {
        // The standard CRC-16/XMODEM check value.
        assertEquals(0x31C3, G7Protocol.serialCrc("123456789"))
    }

    @Test fun advertisedSerialCrcIsTheFirstTwoBytesLowFirst() {
        assertEquals(0x9C10, G7Protocol.advertisedSerialCrc(byteArrayOf(0x10, 0x9C.toByte(), 0x00, 0x04)))
        assertNull(G7Protocol.advertisedSerialCrc(byteArrayOf(0x10)))
        assertNull(G7Protocol.advertisedSerialCrc(null))
    }
}
