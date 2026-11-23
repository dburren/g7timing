// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7.codecheck

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A sensor's side of the exchange, written from the same published description (G7SensorKit's tests). */
internal class SimulatedSensor(code: String, seed: Int) {
    private val pin = U256.fromBytes(code.encodeToByteArray())
    private val priv1 = scalar(seed, 1)
    private val priv2 = scalar(seed, 3)
    val pub1 = P256.multiply(P256.G, priv1)
    val pub2 = P256.multiply(P256.G, priv2)
    private val blinded = P256.scalars.mul(priv2, pin)

    val round1 get() = cert(P256.G, pub1, priv1, scalar(11, 7))
    val round2 get() = cert(P256.G, pub2, priv2, scalar(23, 7))

    fun round3(client1: JpakeRound, client2: JpakeRound): JpakeRound {
        val base = P256.add(P256.add(client1.publicKey, client2.publicKey), pub1)
        return cert(base, P256.multiply(base, blinded), blinded, scalar(37, 7))
    }

    fun sessionKey(client2: JpakeRound, client3: JpakeRound): ByteArray {
        val unblind = P256.scalars.neg(blinded)
        val shared = P256.multiply(P256.add(client3.publicKey, P256.multiply(client2.publicKey, unblind)), priv2)
        return Sha256.hash(shared.x.toBytes()).copyOf(16)
    }

    private fun cert(base: EcPoint, pub: EcPoint, secret: U256, randomizer: U256): JpakeRound {
        val commit = P256.multiply(base, randomizer)
        val c = G7Jpake.transcriptHash(base, commit, pub, PARTY)
        return JpakeRound(pub, commit, P256.scalars.sub(randomizer, P256.scalars.mul(c, secret)))
    }

    companion object {
        val PARTY = byteArrayOf(0x37, 0x56, 0x27, 0x67, 0x56, 0x27)

        fun scalar(seed: Int, mul: Int): U256 {
            val r = U256.fromBytes(ByteArray(32) { (it * mul + seed).toByte() })
            return P256.scalars.add(P256.scalars.reduce(r), U256.ONE)
        }

        /** Whether [round] proves its public point over [base] for the phone's party. */
        fun verifiesClientProof(base: EcPoint, round: JpakeRound): Boolean {
            val c = G7Jpake.transcriptHash(base, round.proofPoint, round.publicKey, "client".encodeToByteArray())
            return P256.add(P256.multiply(base, round.proof), P256.multiply(round.publicKey, c)) == round.proofPoint
        }
    }
}

class G7JpakeTest {
    private class Run(val phoneKey: ByteArray, val sensorKey: ByteArray, val proofsOk: Boolean)

    private fun exchange(phoneCode: String, sensorCode: String, seed: Int = 5): Run {
        val phone = G7Jpake(phoneCode)
        val sensor = SimulatedSensor(sensorCode, seed)
        val s1 = JpakeRound.decode(sensor.round1.encode())!!
        val p1 = JpakeRound.decode(phone.round1)!!
        val s2 = JpakeRound.decode(sensor.round2.encode())!!
        val p2 = JpakeRound.decode(phone.round2)!!
        val p3 = JpakeRound.decode(phone.round3(s1, s2))!!
        val s3 = JpakeRound.decode(sensor.round3(p1, p2).encode())!!
        val phoneKey = phone.sessionKey(s2, s3)
        val sensorKey = sensor.sessionKey(p2, p3)
        val base3 = P256.add(P256.add(p1.publicKey, s1.publicKey), s2.publicKey)
        val proofsOk = SimulatedSensor.verifiesClientProof(P256.G, p1) &&
            SimulatedSensor.verifiesClientProof(P256.G, p2) &&
            SimulatedSensor.verifiesClientProof(base3, p3)
        return Run(phoneKey, sensorKey, proofsOk)
    }

    @Test
    fun sameCodeGivesTheSameKey() {
        val r = exchange("1155", "1155")
        assertContentEquals(r.sensorKey, r.phoneKey)
        assertTrue(r.proofsOk, "the sensor would accept our proofs")
    }

    @Test
    fun wrongCodeGivesDifferentKeys() {
        val r = exchange("1155", "9999")
        assertFalse(r.phoneKey.contentEquals(r.sensorKey))
    }

    @Test
    fun challengeAnswerDependsOnTheKey() {
        val same = exchange("4321", "4321", seed = 99)
        val c = hexBytes("0102030405060708")
        assertContentEquals(G7Jpake.challengeAnswer(same.sensorKey, c), G7Jpake.challengeAnswer(same.phoneKey, c))
        val wrong = exchange("4321", "4322", seed = 99)
        assertFalse(G7Jpake.challengeAnswer(wrong.sensorKey, c).contentEquals(G7Jpake.challengeAnswer(wrong.phoneKey, c)))
    }

    @Test
    fun malformedRoundsAreRefused() {
        val ok = SimulatedSensor("1155", 5).round1.encode()
        assertTrue(JpakeRound.decode(ok) != null)
        assertTrue(JpakeRound.decode(ok.copyOf(159)) == null)
        val bad = ok.copyOf().also { it[40] = (it[40] + 1).toByte() } // y of the public point
        assertTrue(JpakeRound.decode(bad) == null)
    }
}
