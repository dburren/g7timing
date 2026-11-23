// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7.codecheck

/**
 * Just enough NIST P-256 arithmetic for the G7's code exchange: 256-bit unsigned integers, modular
 * arithmetic (Montgomery form) modulo the field prime and the group order, and point addition and
 * scalar multiplication.
 *
 * Not constant-time. That's acceptable here: G7timing only checks whether a sensor uses a code
 * the user already holds, with throwaway keys, and stops before any key is used.
 */

/** A 256-bit unsigned integer as 8 little-endian 32-bit limbs. Immutable by convention. */
internal class U256 private constructor(internal val limbs: IntArray) {
    init {
        require(limbs.size == 8)
    }

    val isZero: Boolean get() = limbs.all { it == 0 }

    operator fun compareTo(other: U256): Int {
        for (i in 7 downTo 0) {
            val a = limbs[i].toUInt()
            val b = other.limbs[i].toUInt()
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    override fun equals(other: Any?) = other is U256 && limbs.contentEquals(other.limbs)

    override fun hashCode() = limbs.contentHashCode()

    override fun toString() = toBytes().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Big-endian, exactly 32 bytes. */
    fun toBytes(): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = limbs[7 - i]
            out[i * 4] = (v ushr 24).toByte()
            out[i * 4 + 1] = (v ushr 16).toByte()
            out[i * 4 + 2] = (v ushr 8).toByte()
            out[i * 4 + 3] = v.toByte()
        }
        return out
    }

    fun bit(i: Int): Boolean = (limbs[i / 32] ushr (i % 32)) and 1 != 0

    companion object {
        val ZERO = U256(IntArray(8))
        val ONE = of(1)

        fun of(v: Long): U256 {
            require(v >= 0)
            return U256(IntArray(8).also { it[0] = v.toInt(); it[1] = (v ushr 32).toInt() })
        }

        /** From big-endian bytes, at most 32 (shorter values are zero-extended). */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): U256 {
            require(length in 0..32) { "more than 256 bits" }
            val l = IntArray(8)
            for (k in 0 until length) {
                val b = bytes[offset + length - 1 - k].toInt() and 0xFF
                l[k / 4] = l[k / 4] or (b shl (8 * (k % 4)))
            }
            return U256(l)
        }

        fun fromHex(hex: String): U256 {
            val h = hex.padStart(64, '0')
            return fromBytes(ByteArray(32) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        internal fun wrap(limbs: IntArray) = U256(limbs)

        /** a + b, and the carry out. */
        internal fun addWithCarry(a: U256, b: U256): Pair<U256, Boolean> {
            val r = IntArray(8)
            var carry = 0uL
            for (i in 0 until 8) {
                val s = a.limbs[i].toUInt().toULong() + b.limbs[i].toUInt().toULong() + carry
                r[i] = s.toUInt().toInt()
                carry = s shr 32
            }
            return U256(r) to (carry != 0uL)
        }

        /** a − b, and whether it borrowed (a < b). */
        internal fun subWithBorrow(a: U256, b: U256): Pair<U256, Boolean> {
            val r = IntArray(8)
            var borrow = 0L
            for (i in 0 until 8) {
                val d = (a.limbs[i].toLong() and MASK) - (b.limbs[i].toLong() and MASK) - borrow
                r[i] = d.toInt()
                borrow = if (d < 0) 1 else 0
            }
            return U256(r) to (borrow != 0L)
        }

        private const val MASK = 0xFFFFFFFFL
    }
}

/**
 * Arithmetic modulo an odd [modulus] greater than 2^255 (true of both P-256's prime and its order),
 * so any 256-bit value is reduced by at most one subtraction.
 */
internal class ModArith(val modulus: U256) {
    init {
        require(modulus.bit(255) && modulus.bit(0))
    }

    /** −modulus⁻¹ mod 2^32, for Montgomery reduction. */
    private val n0inv: UInt = run {
        val n0 = modulus.limbs[0].toUInt()
        var inv = 1u
        repeat(5) { inv *= 2u - n0 * inv }
        (0u - inv)
    }

    /** R² mod modulus, R = 2^256. */
    private val r2: U256 = run {
        var x = U256.subWithBorrow(U256.ZERO, modulus).first // 2^256 − m = R mod m
        repeat(256) { x = add(x, x) }
        x
    }

    /** Reduces any 256-bit value. */
    fun reduce(a: U256): U256 = if (a >= modulus) U256.subWithBorrow(a, modulus).first else a

    fun add(a: U256, b: U256): U256 {
        val (s, carry) = U256.addWithCarry(a, b)
        return if (carry || s >= modulus) U256.subWithBorrow(s, modulus).first else s
    }

    fun sub(a: U256, b: U256): U256 {
        val (d, borrow) = U256.subWithBorrow(a, b)
        return if (borrow) U256.addWithCarry(d, modulus).first else d
    }

    fun neg(a: U256): U256 = sub(U256.ZERO, a)

    /** Montgomery product a·b·R⁻¹ mod m (CIOS). */
    fun montMul(a: U256, b: U256): U256 {
        val n = modulus.limbs
        val t = ULongArray(10)
        for (i in 0 until 8) {
            val bi = b.limbs[i].toUInt().toULong()
            var c = 0uL
            for (j in 0 until 8) {
                val s = t[j] + a.limbs[j].toUInt().toULong() * bi + c
                t[j] = s and LOW
                c = s shr 32
            }
            var s = t[8] + c
            t[8] = s and LOW
            t[9] = s shr 32
            val m = (t[0].toUInt() * n0inv).toULong()
            c = (t[0] + m * n[0].toUInt().toULong()) shr 32
            for (j in 1 until 8) {
                s = t[j] + m * n[j].toUInt().toULong() + c
                t[j - 1] = s and LOW
                c = s shr 32
            }
            s = t[8] + c
            t[7] = s and LOW
            t[8] = t[9] + (s shr 32)
        }
        val r = U256.wrap(IntArray(8) { t[it].toUInt().toInt() })
        return if (t[8] != 0uL || r >= modulus) U256.subWithBorrow(r, modulus).first else r
    }

    fun toMont(a: U256): U256 = montMul(reduce(a), r2)

    fun fromMont(a: U256): U256 = montMul(a, U256.ONE)

    /** Plain a·b mod m. */
    fun mul(a: U256, b: U256): U256 = montMul(montMul(reduce(a), reduce(b)), r2)

    /** a^e for a in Montgomery form; the result is in Montgomery form. */
    fun montPow(a: U256, e: U256): U256 {
        var r = toMont(U256.ONE)
        for (i in 255 downTo 0) {
            r = montMul(r, r)
            if (e.bit(i)) r = montMul(r, a)
        }
        return r
    }

    /** Inverse of a (Montgomery form in and out) by Fermat: modulus must be prime. */
    fun montInv(a: U256): U256 = montPow(a, U256.subWithBorrow(modulus, U256.of(2)).first)

    private companion object {
        const val LOW = 0xFFFFFFFFuL
    }
}

/** An affine P-256 point (plain coordinates), or the point at infinity. */
internal class EcPoint private constructor(val x: U256, val y: U256, val isInfinity: Boolean) {
    constructor(x: U256, y: U256) : this(x, y, false)

    override fun equals(other: Any?) =
        other is EcPoint && isInfinity == other.isInfinity && (isInfinity || (x == other.x && y == other.y))

    override fun hashCode() = if (isInfinity) 0 else x.hashCode() * 31 + y.hashCode()

    /** SEC1 uncompressed: 04 ‖ x ‖ y (65 bytes). The point at infinity is the single byte 00. */
    fun uncompressed(): ByteArray = if (isInfinity) byteArrayOf(0) else byteArrayOf(4) + x.toBytes() + y.toBytes()

    companion object {
        val INFINITY = EcPoint(U256.ZERO, U256.ZERO, true)
    }
}

internal object P256 {
    val p = U256.fromHex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
    val order = U256.fromHex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
    private val b = U256.fromHex("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b")
    val G = EcPoint(
        U256.fromHex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"),
        U256.fromHex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
    )

    val field = ModArith(p)
    val scalars = ModArith(order)

    /** Whether [pt] satisfies y² = x³ − 3x + b. */
    fun isOnCurve(pt: EcPoint): Boolean {
        if (pt.isInfinity) return true
        if (pt.x >= p || pt.y >= p) return false
        val f = field
        val x = f.toMont(pt.x)
        val y = f.toMont(pt.y)
        val lhs = f.montMul(y, y)
        val x3 = f.montMul(f.montMul(x, x), x)
        val threeX = f.add(f.add(x, x), x)
        val rhs = f.add(f.sub(x3, threeX), f.toMont(b))
        return lhs == rhs
    }

    fun add(a: EcPoint, b: EcPoint): EcPoint = toAffine(addJ(toJacobian(a), toJacobian(b)))

    fun multiply(pt: EcPoint, k: U256): EcPoint {
        val base = toJacobian(pt)
        var r = JPoint.INFINITY
        for (i in 255 downTo 0) {
            r = doubleJ(r)
            if (k.bit(i)) r = addJ(r, base)
        }
        return toAffine(r)
    }

    // ------------------------------------------------- Jacobian coordinates, Montgomery form

    private class JPoint(val x: U256, val y: U256, val z: U256) {
        val isInfinity get() = z.isZero

        companion object {
            val INFINITY = JPoint(U256.ZERO, U256.ZERO, U256.ZERO)
        }
    }

    private fun toJacobian(a: EcPoint): JPoint =
        if (a.isInfinity) JPoint.INFINITY else JPoint(field.toMont(a.x), field.toMont(a.y), field.toMont(U256.ONE))

    private fun toAffine(a: JPoint): EcPoint {
        if (a.isInfinity) return EcPoint.INFINITY
        val f = field
        val zInv = f.montInv(a.z)
        val zInv2 = f.montMul(zInv, zInv)
        val x = f.montMul(a.x, zInv2)
        val y = f.montMul(a.y, f.montMul(zInv2, zInv))
        return EcPoint(f.fromMont(x), f.fromMont(y))
    }

    /** dbl-2001-b (a = −3). */
    private fun doubleJ(a: JPoint): JPoint {
        if (a.isInfinity || a.y.isZero) return JPoint.INFINITY
        val f = field
        val delta = f.montMul(a.z, a.z)
        val gamma = f.montMul(a.y, a.y)
        val beta = f.montMul(a.x, gamma)
        val t = f.montMul(f.sub(a.x, delta), f.add(a.x, delta))
        val alpha = f.add(f.add(t, t), t)
        val beta4 = f.add(f.add(beta, beta), f.add(beta, beta))
        val beta8 = f.add(beta4, beta4)
        val x3 = f.sub(f.montMul(alpha, alpha), beta8)
        val yz = f.add(a.y, a.z)
        val z3 = f.sub(f.sub(f.montMul(yz, yz), gamma), delta)
        val gamma2 = f.montMul(gamma, gamma)
        val g2x2 = f.add(gamma2, gamma2)
        val g2x4 = f.add(g2x2, g2x2)
        val g2x8 = f.add(g2x4, g2x4)
        val y3 = f.sub(f.montMul(alpha, f.sub(beta4, x3)), g2x8)
        return JPoint(x3, y3, z3)
    }

    /** add-2007-bl. */
    private fun addJ(a: JPoint, b: JPoint): JPoint {
        if (a.isInfinity) return b
        if (b.isInfinity) return a
        val f = field
        val z1z1 = f.montMul(a.z, a.z)
        val z2z2 = f.montMul(b.z, b.z)
        val u1 = f.montMul(a.x, z2z2)
        val u2 = f.montMul(b.x, z1z1)
        val s1 = f.montMul(f.montMul(a.y, b.z), z2z2)
        val s2 = f.montMul(f.montMul(b.y, a.z), z1z1)
        val h = f.sub(u2, u1)
        val sd = f.sub(s2, s1)
        if (h.isZero) return if (sd.isZero) doubleJ(a) else JPoint.INFINITY
        val r = f.add(sd, sd)
        val h2 = f.add(h, h)
        val i = f.montMul(h2, h2)
        val j = f.montMul(h, i)
        val v = f.montMul(u1, i)
        val x3 = f.sub(f.sub(f.montMul(r, r), j), f.add(v, v))
        val s1j = f.montMul(s1, j)
        val y3 = f.sub(f.montMul(r, f.sub(v, x3)), f.add(s1j, s1j))
        val zs = f.add(a.z, b.z)
        val z3 = f.montMul(f.sub(f.sub(f.montMul(zs, zs), z1z1), z2z2), h)
        return JPoint(x3, y3, z3)
    }
}
