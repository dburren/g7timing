// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7.codecheck

private val rng = java.security.SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }
