// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0
//
// Message sequence and characteristic UUIDs from LoopKit G7SensorKit (MIT), G7Authenticator.swift
// and BluetoothServices.swift, next-dev branch. See NOTICE.

package com.bionicwookiee.g7timing.g7.codecheck

import com.bionicwookiee.g7timing.ble.BleException
import com.bionicwookiee.g7timing.ble.GattConnection
import com.bionicwookiee.g7timing.ble.normUuid
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** What a code check found. */
sealed interface CodeCheckOutcome {
    /** The sensor proved it uses the code. */
    data object Matches : CodeCheckOutcome

    /** The sensor answered, and its answer shows it uses a different code. */
    data object DoesNotMatch : CodeCheckOutcome

    /** No answer either way (link lost, timed out, unexpected reply). */
    data class Inconclusive(val reason: String) : CodeCheckOutcome
}

/**
 * Checks whether a G7 uses a 4-digit sensor code, on a connection whose services have been listed
 * (i.e. a sensor that responds).
 *
 * It runs the code exchange the sensor's own apps start pairing with, then sends one challenge. The
 * sensor's answer is correct only if both sides used the same code. G7timing stops there: it
 * never answers the sensor's challenge back, so it never signs in, sends no certificates and asks
 * for no bond. The caller should disconnect as soon as this returns.
 *
 * The keys are made when this is created, before connecting: a sensor drops the link after about
 * 4 seconds without hearing from the phone.
 */
class G7CodeCheck(code: String, private val stepTimeoutMs: Long = STEP_TIMEOUT_MS) {
    init {
        require(isSensorCode(code))
    }

    private val jpake = G7Jpake(code)

    suspend fun run(conn: GattConnection): CodeCheckOutcome = try {
        conn.enableNotifications(SERVICE, CERTIFICATE)
        conn.enableNotifications(SERVICE, AUTHENTICATION)
        val s1 = requestRound(conn, 0) ?: return CodeCheckOutcome.Inconclusive("the sensor's first round wasn't valid")
        writeChunks(conn, jpake.round1)
        val s2 = requestRound(conn, 1) ?: return CodeCheckOutcome.Inconclusive("the sensor's second round wasn't valid")
        writeChunks(conn, jpake.round2)
        val ours3 = jpake.round3(s1, s2)
        val s3 = requestRound(conn, 2) ?: return CodeCheckOutcome.Inconclusive("the sensor's third round wasn't valid")
        val key = jpake.sessionKey(s2, s3)
        writeChunks(conn, ours3)

        val challenge = secureRandomBytes(8)
        conn.write(SERVICE, AUTHENTICATION, byteArrayOf(0x02) + challenge + byteArrayOf(DISPLAY_TYPE_PHONE), withResponse = true)
        val reply = awaitAuthentication(conn, 0x03)
        if (reply.size < 17) {
            CodeCheckOutcome.Inconclusive("the sensor's answer was too short")
        } else if (reply.copyOfRange(1, 9).contentEquals(G7Jpake.challengeAnswer(key, challenge))) {
            CodeCheckOutcome.Matches
        } else {
            CodeCheckOutcome.DoesNotMatch
        }
    } catch (e: BleException) {
        CodeCheckOutcome.Inconclusive(e.message ?: "Bluetooth error")
    } catch (e: TimeoutCancellationException) {
        CodeCheckOutcome.Inconclusive("the sensor stopped answering")
    } catch (e: ClosedReceiveChannelException) {
        CodeCheckOutcome.Inconclusive("the link was lost")
    }

    /** Asks for one of the sensor's rounds and collects its 160 bytes from the certificate characteristic. */
    private suspend fun requestRound(conn: GattConnection, round: Int): JpakeRound? {
        conn.write(SERVICE, AUTHENTICATION, byteArrayOf(0x0A, round.toByte()), withResponse = true)
        var buf = ByteArray(0)
        withTimeout(stepTimeoutMs) {
            while (buf.size < JpakeRound.SIZE) {
                val n = conn.notifications.receive()
                // The sensor's acknowledgements on the authentication characteristic are ignored.
                if (normUuid(n.characteristic) == CERTIFICATE) buf += n.value
            }
        }
        return JpakeRound.decode(buf.copyOf(JpakeRound.SIZE))
    }

    private suspend fun awaitAuthentication(conn: GattConnection, opcode: Int): ByteArray = withTimeout(stepTimeoutMs) {
        while (true) {
            val n = conn.notifications.receive()
            if (normUuid(n.characteristic) == AUTHENTICATION && n.value.isNotEmpty() && n.value[0].toInt() == opcode) return@withTimeout n.value
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    }

    /** Our rounds go to the certificate characteristic in 20-byte writes without response. */
    private suspend fun writeChunks(conn: GattConnection, data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val end = minOf(i + CHUNK, data.size)
            conn.write(SERVICE, CERTIFICATE, data.copyOfRange(i, end), withResponse = false)
            i = end
            if (i < data.size) delay(CHUNK_INTERVAL_MS)
        }
    }

    companion object {
        const val SERVICE = "F8083532-849E-531C-C594-30F1F86A4EA5"
        const val AUTHENTICATION = "F8083535-849E-531C-C594-30F1F86A4EA5"
        const val CERTIFICATE = "F8083538-849E-531C-C594-30F1F86A4EA5"

        /** The display type sent with the challenge (phone). Nothing is ever signed in as it. */
        private const val DISPLAY_TYPE_PHONE: Byte = 2
        private const val CHUNK = 20
        private const val CHUNK_INTERVAL_MS = 40L
        private const val STEP_TIMEOUT_MS = 4_000L

        /** A sensor code is the 4 digits printed on the applicator. */
        fun isSensorCode(text: String) = text.length == 4 && text.all { it in '0'..'9' }
    }
}
