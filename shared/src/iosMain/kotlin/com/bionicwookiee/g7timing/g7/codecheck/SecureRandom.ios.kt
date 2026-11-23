// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalForeignApi::class)

package com.bionicwookiee.g7timing.g7.codecheck

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed ($status)" }
    return out
}
