// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7.codecheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
internal fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class CryptoTest {
    @Test
    fun sha256KnownAnswers() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hash(ByteArray(0)).hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hash("abc".encodeToByteArray()).hex())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun aesFips197Example() {
        val key = hexBytes("000102030405060708090a0b0c0d0e0f")
        val pt = hexBytes("00112233445566778899aabbccddeeff")
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", Aes128.encryptBlock(key, pt).hex())
    }

    @Test
    fun p256KnownMultiples() {
        assertTrue(P256.isOnCurve(P256.G))
        assertEquals(P256.G, P256.multiply(P256.G, U256.ONE))
        val g2 = P256.multiply(P256.G, U256.of(2))
        assertEquals("7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978", g2.x.toString())
        assertEquals("07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1", g2.y.toString())
        assertEquals(g2, P256.add(P256.G, P256.G))
        val g3 = P256.multiply(P256.G, U256.of(3))
        assertEquals("5ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c", g3.x.toString())
        assertEquals("8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032", g3.y.toString())
        assertEquals(g3, P256.add(g2, P256.G))
        assertTrue(P256.multiply(P256.G, P256.order).isInfinity)
        val nMinus1 = U256.subWithBorrow(P256.order, U256.ONE).first
        val minusG = P256.multiply(P256.G, nMinus1)
        assertEquals(P256.G.x, minusG.x)
        assertEquals(P256.field.neg(P256.G.y), minusG.y)
        assertTrue(P256.add(P256.G, minusG).isInfinity)
    }
}
