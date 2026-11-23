// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0
//
// The protocol facts here (the secret's encoding, the 160-byte round layout, the transcript's order
// and length prefixes, the party identifier, the fixed round-3 randomizer, round 3's base and the key
// derivation) are from LoopKit G7SensorKit (MIT), G7JPAKE.swift and G7PCert.swift, next-dev branch.
// See NOTICE.

package com.bionicwookiee.g7timing.g7.codecheck

/** Secure random bytes from the platform. */
internal expect fun secureRandomBytes(count: Int): ByteArray

/**
 * One round of the G7's code exchange as sent over the air: a public point, the proof's
 * commitment point and the proof scalar, each coordinate 32 bytes big-endian (160 bytes).
 */
internal class JpakeRound(val publicKey: EcPoint, val proofPoint: EcPoint, val proof: U256) {
    fun encode(): ByteArray =
        publicKey.x.toBytes() + publicKey.y.toBytes() + proofPoint.x.toBytes() + proofPoint.y.toBytes() + proof.toBytes()

    companion object {
        const val SIZE = 160

        /** Null unless [bytes] is 160 bytes holding two points on the curve. */
        fun decode(bytes: ByteArray): JpakeRound? {
            if (bytes.size != SIZE) return null
            fun c(i: Int) = U256.fromBytes(bytes, i * 32, 32)
            val pub = EcPoint(c(0), c(1))
            val commit = EcPoint(c(2), c(3))
            if (!P256.isOnCurve(pub) || !P256.isOnCurve(commit)) return null
            return JpakeRound(pub, commit, c(4))
        }
    }
}

/**
 * The phone's side of the G7's code exchange: EC-J-PAKE on P-256, whose shared secret is the
 * sensor's 4-digit code. Both sides end with the same key only if they used the same code, which
 * the sensor's answer to a challenge then shows. G7timing uses this only to see whether a sensor
 * uses a code; it never goes on to sign in.
 */
internal class G7Jpake(code: String, private val random: (Int) -> ByteArray = ::secureRandomBytes) {
    /** The code as a big-endian integer over its ASCII digits. */
    private val pin = U256.fromBytes(code.encodeToByteArray())

    private val keyPair1 = makeKeyPair()
    private val keyPair2 = makeKeyPair()

    /** Our round 1 (sent after the sensor's round 1). */
    val round1: ByteArray = proveKnowledge(P256.G, keyPair1.second, keyPair1.first).encode()

    /** Our round 2 (sent after the sensor's round 2). */
    val round2: ByteArray = proveKnowledge(P256.G, keyPair2.second, keyPair2.first).encode()

    private val blinded = P256.scalars.mul(keyPair2.first, pin)

    /** Our round 3, over the sensor's first two rounds. */
    fun round3(sensor1: JpakeRound, sensor2: JpakeRound): ByteArray {
        val base = P256.add(P256.add(keyPair1.second, sensor1.publicKey), sensor2.publicKey)
        val pub = P256.multiply(base, blinded)
        return proveKnowledge(base, pub, blinded, ROUND3_RANDOMIZER).encode()
    }

    /** The 16-byte AES key: the first half of SHA-256 of the shared point's x coordinate. */
    fun sessionKey(sensor2: JpakeRound, sensor3: JpakeRound): ByteArray {
        val unblind = P256.scalars.neg(blinded)
        val shared = P256.multiply(P256.add(sensor3.publicKey, P256.multiply(sensor2.publicKey, unblind)), keyPair2.first)
        return Sha256.hash(shared.x.toBytes()).copyOf(16)
    }

    private fun makeKeyPair(): Pair<U256, EcPoint> {
        val k = randomScalar()
        return k to P256.multiply(P256.G, k)
    }

    /** Uniform-ish in [1, n − 2], as the sensor's own implementation draws them. */
    private fun randomScalar(): U256 {
        val upper = U256.subWithBorrow(P256.order, U256.of(2)).first
        val r = U256.fromBytes(random(32))
        val reduced = if (r >= upper) U256.subWithBorrow(r, upper).first else r
        return U256.addWithCarry(reduced, U256.ONE).first
    }

    /** A Schnorr proof that [pub] = [base]·[secret]. */
    private fun proveKnowledge(base: EcPoint, pub: EcPoint, secret: U256, randomizer: U256 = randomScalar()): JpakeRound {
        val commit = P256.multiply(base, randomizer)
        val challenge = transcriptHash(base, commit, pub, OWN_PARTY)
        val proof = P256.scalars.sub(randomizer, P256.scalars.mul(challenge, secret))
        return JpakeRound(pub, commit, proof)
    }

    internal companion object {
        private val OWN_PARTY = "client".encodeToByteArray()

        /** The sensor expects this fixed randomizer in our round-3 proof (G7SensorKit). */
        private val ROUND3_RANDOMIZER = U256.fromHex("fbc971b837e9491e45a4179ed33865c508a1e0a1d350f5af0f96370695fdc393")

        /** SHA-256 over the three points and the party, each with a 4-byte big-endian length, mod n. */
        fun transcriptHash(base: EcPoint, commit: EcPoint, pub: EcPoint, party: ByteArray): U256 {
            val parts = listOf(base.uncompressed(), commit.uncompressed(), pub.uncompressed(), party)
            var buf = ByteArray(0)
            for (p in parts) buf += byteArrayOf((p.size ushr 24).toByte(), (p.size ushr 16).toByte(), (p.size ushr 8).toByte(), p.size.toByte()) + p
            return P256.scalars.reduce(U256.fromBytes(Sha256.hash(buf)))
        }

        /** The challenge transform: AES-128 of the 8-byte challenge written twice; the first 8 bytes. */
        fun challengeAnswer(key: ByteArray, challenge: ByteArray): ByteArray {
            require(challenge.size == 8)
            return Aes128.encryptBlock(key, challenge + challenge).copyOf(8)
        }
    }
}
