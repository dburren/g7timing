// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.g7

/**
 * What G7timing reads from the 2D code (a GS1 Data Matrix) on a G7 sensor's applicator or box.
 *
 * Both carry GS1 fields including (01) product number and (21) serial number. The applicator's
 * also has (11) made, (17) expires and (240) the 4-digit pairing code; only the serial is used.
 */
data class ApplicatorCode(
    /** GS1 (21): the sensor's serial number. */
    val serial: String,
)

object ApplicatorCodeParser {
    private const val GS = '\u001D'

    /** Fixed-length GS1 Application Identifiers, by AI → data length. */
    private val FIXED = mapOf(
        "00" to 18, "01" to 14, "02" to 14,
        "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6, "20" to 2,
    )

    /**
     * Parses a scanned code. Accepts the raw element string (fields separated by the GS
     * character, optionally preceded by a symbology identifier such as "]d2" or a leading GS)
     * and the human-readable form "(01)…(21)…". Returns null unless it holds a serial number.
     */
    fun parse(raw: String): ApplicatorCode? {
        val fields = if (raw.trimStart().startsWith("(")) parseBracketed(raw.trim()) else parseElementString(raw)
        val serial = fields["21"]?.takeIf { s -> s.isNotEmpty() && s.all { it.isLetterOrDigit() } } ?: return null
        return ApplicatorCode(serial = serial)
    }

    /**
     * Reads a serial number typed by hand. Spaces and dashes are dropped, and a full code typed
     * in its "(01)…(21)…" form is accepted too. Returns null unless 6–20 letters or digits remain
     * (G7 and Stelo serial numbers are 12 digits).
     */
    fun parseTypedSerial(text: String): String? {
        val t = text.trim()
        if (t.contains("(21)")) return parse(t)?.serial
        val s = t.filterNot { it == ' ' || it == '-' }.removePrefix("SN").removePrefix("sn")
        return s.takeIf { it.length in 6..20 && it.all { c -> c.isLetterOrDigit() && c.code < 128 } }
    }

    private fun parseBracketed(s: String): Map<String, String> =
        Regex("""\((\d{2,4})\)([^(]*)""").findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun parseElementString(input: String): Map<String, String> {
        var s = input
        for (prefix in listOf("]d2", "]C1", "]Q3", "]e0")) if (s.startsWith(prefix)) s = s.removePrefix(prefix)
        s = s.trimStart(GS).trimEnd()
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i + 2 <= s.length) {
            val ai = aiAt(s, i) ?: break
            i += ai.length
            val fixed = FIXED[ai] ?: if (ai.length == 4 && ai[0] == '3' && ai[1] in '1'..'6') 6 else null
            val value = if (fixed != null) {
                if (i + fixed > s.length) break
                s.substring(i, i + fixed).also { i += fixed }
            } else {
                val end = s.indexOf(GS, i).let { if (it < 0) s.length else it }
                s.substring(i, end).also { i = end }
            }
            out[ai] = value
            while (i < s.length && s[i] == GS) i++
        }
        return out
    }

    /** The AI starting at [i]: 2 digits, or 3 for 23x–25x and 4 for 31x–36x (GS1 General Specifications). */
    private fun aiAt(s: String, i: Int): String? {
        val two = s.substring(i, i + 2)
        if (!two.all { it.isDigit() }) return null
        val len = when (two) {
            "23", "24", "25", "41", "42", "70", "71", "72" -> 3
            "31", "32", "33", "34", "35", "36", "39", "43", "80", "81", "82" -> 4
            else -> 2
        }
        if (i + len > s.length) return null
        return s.substring(i, i + len).takeIf { ai -> ai.all { it.isDigit() } }
    }
}
