// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** All serial numbers here are made up. */
class ApplicatorCodeTest {
    private val gs = "\u001D"

    @Test fun elementStringWithSeparator() {
        // (01) product, (21) serial ended by GS, (11) made, (17) expires, (240) pairing code.
        val raw = "0100386270004512" + "21123456789012" + gs + "11251101" + "17270430" + "2401234"
        assertEquals(ApplicatorCode("123456789012"), ApplicatorCodeParser.parse(raw))
    }

    @Test fun symbologyIdentifierAndLeadingSeparatorAreIgnored() {
        val body = "0100386270004512" + "21123456789012" + gs + "2401234"
        assertEquals("123456789012", ApplicatorCodeParser.parse("]d2$body")?.serial)
        assertEquals("123456789012", ApplicatorCodeParser.parse("$gs$body")?.serial)
    }

    @Test fun humanReadableForm() {
        val raw = "(01)00386270004512(21)123456789012(11)251101(17)270430(240)1234"
        assertEquals(ApplicatorCode("123456789012"), ApplicatorCodeParser.parse(raw))
    }

    @Test fun serialLastNeedsNoSeparator() {
        assertEquals(ApplicatorCode("123456789012"), ApplicatorCodeParser.parse("0100386270004512" + "21123456789012"))
    }

    @Test fun boxCodeWithLotNumber() {
        // A box: (01) product, (17) expires, (10) lot ended by GS, (21) serial.
        val raw = "0100386270004512" + "17270430" + "10ABC123" + gs + "21123456789012"
        assertEquals(ApplicatorCode("123456789012"), ApplicatorCodeParser.parse(raw))
    }

    @Test fun codesWithoutASerialAreRejected() {
        assertNull(ApplicatorCodeParser.parse("https://example.com/x"))
        assertNull(ApplicatorCodeParser.parse("0100386270004512"))
        assertNull(ApplicatorCodeParser.parse(""))
    }

    @Test
    fun typedSerialIgnoresSpacesAndDashes() {
        assertEquals("100000000001", ApplicatorCodeParser.parseTypedSerial("100000000001"))
        assertEquals("100000000001", ApplicatorCodeParser.parseTypedSerial(" 1000 0000-0001 "))
        assertEquals("100000000001", ApplicatorCodeParser.parseTypedSerial("SN 100000000001"))
        assertEquals("100000000001", ApplicatorCodeParser.parseTypedSerial("(01)00386270004512(21)100000000001(17)280101"))
    }

    @Test
    fun typedSerialRejectsWhatIsntOne() {
        assertNull(ApplicatorCodeParser.parseTypedSerial(""))
        assertNull(ApplicatorCodeParser.parseTypedSerial("1234"))
        assertNull(ApplicatorCodeParser.parseTypedSerial("1000.0000.0001"))
        assertNull(ApplicatorCodeParser.parseTypedSerial("123456789012345678901"))
    }
}
