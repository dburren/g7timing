// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7.codecheck

/** AES-128 single-block encryption (FIPS-197), for the G7's challenge. Encrypt only. */
internal object Aes128 {
    private val SBOX: IntArray = run {
        // Built from the field inverse and the affine transform rather than typed out.
        val s = IntArray(256)
        var p = 1
        var q = 1
        do {
            p = p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1B else 0)
            p = p and 0xFF
            q = q xor (q shl 1)
            q = q xor (q shl 2)
            q = q xor (q shl 4)
            q = q and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
            s[p] = (x xor 0x63) and 0xFF
        } while (p != 1)
        s[0] = 0x63
        s
    }

    private fun rotl8(x: Int, n: Int) = ((x shl n) or (x ushr (8 - n))) and 0xFF

    private fun xtime(x: Int) = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF

    private fun expandKey(key: ByteArray): IntArray {
        require(key.size == 16)
        val w = IntArray(176)
        for (i in 0 until 16) w[i] = key[i].toInt() and 0xFF
        var rcon = 1
        for (i in 16 until 176 step 4) {
            var t0 = w[i - 4]; var t1 = w[i - 3]; var t2 = w[i - 2]; var t3 = w[i - 1]
            if (i % 16 == 0) {
                val tmp = t0
                t0 = SBOX[t1] xor rcon; t1 = SBOX[t2]; t2 = SBOX[t3]; t3 = SBOX[tmp]
                rcon = xtime(rcon)
            }
            w[i] = w[i - 16] xor t0; w[i + 1] = w[i - 15] xor t1; w[i + 2] = w[i - 14] xor t2; w[i + 3] = w[i - 13] xor t3
        }
        return w
    }

    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == 16)
        val w = expandKey(key)
        val s = IntArray(16) { (block[it].toInt() and 0xFF) xor w[it] }
        for (round in 1..10) {
            for (i in 0 until 16) s[i] = SBOX[s[i]]
            // ShiftRows (state is column-major: s[col*4 + row]).
            val t = s.copyOf()
            for (c in 0 until 4) for (r in 0 until 4) s[c * 4 + r] = t[((c + r) % 4) * 4 + r]
            if (round != 10) {
                for (c in 0 until 4) {
                    val a0 = s[c * 4]; val a1 = s[c * 4 + 1]; val a2 = s[c * 4 + 2]; val a3 = s[c * 4 + 3]
                    val all = a0 xor a1 xor a2 xor a3
                    s[c * 4] = a0 xor all xor xtime(a0 xor a1)
                    s[c * 4 + 1] = a1 xor all xor xtime(a1 xor a2)
                    s[c * 4 + 2] = a2 xor all xor xtime(a2 xor a3)
                    s[c * 4 + 3] = a3 xor all xor xtime(a3 xor a0)
                }
            }
            for (i in 0 until 16) s[i] = s[i] xor w[round * 16 + i]
        }
        return ByteArray(16) { s[it].toByte() }
    }
}
