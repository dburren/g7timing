// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.monitor

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {
    @Test fun minutesAndSeconds() {
        assertEquals("0:00", formatMinutesSeconds(0))
        assertEquals("0:07", formatMinutesSeconds(7))
        assertEquals("1:00", formatMinutesSeconds(60))
        assertEquals("3:53", formatMinutesSeconds(233))
        assertEquals("75:02", formatMinutesSeconds(75 * 60 + 2))
        assertEquals("0:00", formatMinutesSeconds(-5))
    }
}
