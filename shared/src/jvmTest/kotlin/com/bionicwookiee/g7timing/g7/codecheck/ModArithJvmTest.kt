// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7.codecheck

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks the 256-bit modular arithmetic against java.math.BigInteger on random and edge values. */
class ModArithJvmTest {
    private fun big(u: U256) = BigInteger(1, u.toBytes())
    private fun u(b: BigInteger) = U256.fromHex(b.toString(16))

    private fun values(m: BigInteger, rnd: Random): List<BigInteger> {
        val edge = listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, m - BigInteger.ONE, m - BigInteger.TWO, m.shiftRight(1))
        return edge + List(300) { BigInteger(1, rnd.nextBytes(32)).mod(m) }
    }

    @Test
    fun matchesBigInteger() {
        val rnd = Random(7)
        for (arith in listOf(P256.field, P256.scalars)) {
            val m = big(arith.modulus)
            val vs = values(m, rnd)
            for (i in vs.indices) {
                val a = vs[i]
                val b = vs[(i * 7 + 3) % vs.size]
                assertEquals(a.add(b).mod(m), big(arith.add(u(a), u(b))))
                assertEquals(a.subtract(b).mod(m), big(arith.sub(u(a), u(b))))
                assertEquals(a.multiply(b).mod(m), big(arith.mul(u(a), u(b))))
                if (a.signum() != 0) {
                    val inv = arith.fromMont(arith.montInv(arith.toMont(u(a))))
                    assertEquals(a.modInverse(m), big(inv))
                }
            }
            // reduce() of any 256-bit value.
            val any = BigInteger(1, ByteArray(32) { -1 })
            assertEquals(any.mod(m), big(arith.reduce(u(any))))
        }
    }

    @Test
    fun scalarMultiplicationIsFastEnough() {
        val k = U256.fromBytes(Random(1).nextBytes(32))
        repeat(3) { P256.multiply(P256.G, k) } // warm up
        val t0 = System.nanoTime()
        repeat(10) { P256.multiply(P256.G, k) }
        val ms = (System.nanoTime() - t0) / 1e6 / 10
        println("P-256 scalar multiplication: %.1f ms".format(ms))
        kotlin.test.assertTrue(ms < 200, "a code check needs about 8 of these within the sensor's 4 s; took $ms ms each")
    }
}
