// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.monitor

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private fun Int.pad(n: Int = 2) = toString().padStart(n, '0')

fun Instant.toLocalTimeString(tz: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = toLocalDateTime(tz)
    return "${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}"
}

fun Instant.toLocalDateTimeString(tz: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = toLocalDateTime(tz)
    return "${t.year}-${t.month.ordinal.plus(1).pad()}-${t.day.pad()} ${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}"
}

/** Elapsed time as minutes and seconds, e.g. 233 → "3:53". Minutes keep counting past 60. */
fun formatMinutesSeconds(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
