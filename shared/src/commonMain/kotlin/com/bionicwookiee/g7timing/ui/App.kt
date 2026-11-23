// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.bionicwookiee.g7timing.ble.BleAvailability
import com.bionicwookiee.g7timing.monitor.CodeStatus
import com.bionicwookiee.g7timing.monitor.MonitorController
import com.bionicwookiee.g7timing.monitor.Phase
import com.bionicwookiee.g7timing.monitor.Transmitter
import com.bionicwookiee.g7timing.monitor.TransmitterStatus
import com.bionicwookiee.g7timing.monitor.UiState
import com.bionicwookiee.g7timing.monitor.formatMinutesSeconds
import com.bionicwookiee.g7timing.monitor.toLocalDateTimeString
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Root composable shared by Android and iOS.
 * [onKeepScreenOn] lets the platform keep the display awake while a session is running.
 */
@Composable
fun G7timingApp(
    controller: MonitorController,
    darkTheme: Boolean = false,
    onKeepScreenOn: (Boolean) -> Unit = {},
    /** Opens the platform camera to read an applicator's code; null where there's no camera. */
    onScanApplicator: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val availability by controller.availability.collectAsState()

    LaunchedEffect(state.phase) { onKeepScreenOn(state.phase != Phase.Idle) }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("G7timing", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

                val startedAt = state.startedAt
                if (state.phase != Phase.Idle && startedAt != null) {
                    Text(
                        "Started: ${startedAt.toLocalDateTimeString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                AvailabilityBanner(availability)

                if (state.phase == Phase.Idle) {
                    Text(
                        "G7timing lists every G7 transmitter it sees, and shows each time one that accepts a Bluetooth " +
                            "connection but doesn't respond is seen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ApplicatorPanel(state, onScanApplicator, controller::onSerialEntered, controller::onSensorCodeEntered, controller::clearApplicator)
                    Button(
                        onClick = controller::start,
                        enabled = availability == BleAvailability.Ready,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start") }
                } else {
                    ApplicatorPanel(state, onScanApplicator, controller::onSerialEntered, controller::onSensorCodeEntered, controller::clearApplicator)
                    Session(state, controller.identifierLabel, onStop = controller::stop)
                    EventLog(state.log)
                }

                Text(
                    "G7timing does not read glucose and is not a medical device. It never pairs (bonds) with a sensor " +
                        "and never signs in to one. It connects once to each G7 nearby (including working sensors) to " +
                        "see whether it responds. Scanning the code on a sensor's applicator or box, or typing its serial number, identifies " +
                        "its transmitter from what the transmitter broadcasts. With a sensor code instead, it connects to each G7 that " +
                        "responds (up to 2 tries) and runs the first part of pairing to see whether it uses that code, then disconnects " +
                        "before pairing. The serial number and code are used only for that and aren't saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class Entry { Serial, Code }

/**
 * The sensor being looked for, if any, and the ways to pick one: scan the code on its applicator
 * or box, type its serial number, or type its 4-digit sensor code. While a session runs it also
 * says whether that sensor's transmitter has been found yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApplicatorPanel(
    state: UiState,
    onScan: (() -> Unit)?,
    onEnterSerial: (String) -> Boolean,
    onEnterCode: (String) -> Boolean,
    onClear: () -> Unit,
) {
    val applicator = state.applicator
    val code = state.sensorCode
    var entry by remember { mutableStateOf<Entry?>(null) }
    var text by remember { mutableStateOf("") }
    val submit = {
        val ok = when (entry) {
            Entry.Serial -> onEnterSerial(text)
            Entry.Code -> onEnterCode(text)
            null -> false
        }
        if (ok) {
            entry = null
            text = ""
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                applicator != null -> {
                    Text("Looking for the sensor with serial number", style = MaterialTheme.typography.bodyMedium)
                    BigValue(applicator.serialGrouped)
                    Text(
                        (if (applicator.typed) "Check this matches the serial number on the applicator or box. If a digit is wrong, its transmitter won't be recognised. " else "") +
                            "Below, it's called sensor …${applicator.serialEnding}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.phase == Phase.Running) {
                        val found = state.transmitters.firstOrNull { state.isTarget(it) }
                        Text(
                            if (found != null) "Its transmitter is ${found.name ?: found.id}."
                            else "Its transmitter hasn't been heard yet. It's recognised as soon as it next wakes and advertises.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                code != null -> {
                    Text("Looking for the sensor with code", style = MaterialTheme.typography.bodyMedium)
                    BigValue(code)
                    Text(
                        "Check this matches the code on the applicator. Each G7 that responds is checked for it, up to 2 tries each, " +
                            "and the search carries on after a match in case another sensor nearby shares the code. A G7 that doesn't " +
                            "respond, such as an unstarted sensor still in its applicator, can't be checked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.phase == Phase.Running) {
                        val found = state.transmitters.filter { state.isTarget(it) }.map { it.name ?: it.id }
                        Text(
                            when (found.size) {
                                0 -> "No transmitter has shown it uses this code yet."
                                1 -> "Uses this code: ${found.single()}."
                                else -> "More than one transmitter uses this code: ${found.joinToString()}."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                else -> Text(
                    "To pick out a particular sensor's transmitter, scan the square code on its applicator or box, or type its " +
                        "serial number or its 4-digit sensor code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            entry?.let { e ->
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = if (e == Entry.Code) it.filter(Char::isDigit).take(4) else it },
                    label = { Text(if (e == Entry.Serial) "Serial number" else "Sensor code") },
                    placeholder = { Text(if (e == Entry.Serial) "12 digits" else "4 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (e == Entry.Code && applicator != null) {
                    Text(
                        "Using a code replaces the serial number being looked for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.applicatorError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry != null) {
                    Button(onClick = submit, enabled = text.isNotBlank()) {
                        Text(if (entry == Entry.Serial) "Use this serial number" else "Use this code")
                    }
                    TextButton(onClick = { entry = null; text = "" }) { Text("Cancel") }
                } else {
                    if (onScan != null) OutlinedButton(onClick = onScan) { Text("Scan applicator or box") }
                    OutlinedButton(onClick = { entry = Entry.Serial }) { Text("Type serial number") }
                    OutlinedButton(onClick = { entry = Entry.Code }) { Text("Type sensor code") }
                    if (applicator != null || code != null) TextButton(onClick = onClear) { Text("Clear") }
                }
            }
        }
    }
}

/** A serial number or code, large enough to check digit by digit. */
@Composable
private fun BigValue(value: String) {
    Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}

@Composable
private fun AvailabilityBanner(a: BleAvailability) {
    val msg = when (a) {
        BleAvailability.Ready -> return
        BleAvailability.Unknown -> "Checking Bluetooth…"
        BleAvailability.PoweredOff -> "Bluetooth is off. Turn it on to continue."
        BleAvailability.Unauthorized -> "G7timing does not have Bluetooth permission. Allow it in Settings."
        BleAvailability.Unsupported -> "This device does not support Bluetooth Low Energy."
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(msg, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("STOP", fontWeight = FontWeight.Bold) }
}

@Composable
private fun Session(state: UiState, idLabel: String, onStop: () -> Unit) {
    val now by rememberNow()
    // Grouped by status. Within a group, cards stay in the order their transmitters were first
    // seen (the state list is in first-seen order and sortedBy is stable), so a card only moves
    // when its status changes.
    val list = state.transmitters.sortedBy { it.status.order }
    val cantBeChecked = list.count { it.status == TransmitterStatus.CantBeChecked }
    // The transmitter being looked for, once found, is what the screen is about.
    val (mine, others) = list.partition { state.isTarget(it) }
    val targetLabel = state.applicator?.label ?: "the sensor with code ${state.sensorCode}"
    val found = mine.isNotEmpty()

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Column {
            Text(
                when {
                    found -> "Following $targetLabel: ${mine.joinToString { it.name ?: "its transmitter" }}"
                    list.isEmpty() -> "Looking for G7 transmitters…"
                    cantBeChecked == 1 -> "1 transmitter accepts a connection but doesn't respond"
                    cantBeChecked > 1 -> "$cantBeChecked transmitters accept a connection but don't respond"
                    else -> "None that accepts a connection without responding, yet"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (list.isNotEmpty()) {
                Text(
                    "G7s seen: ${list.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (cantBeChecked == 0 && !found) {
        Text(
            "A quiet G7 wakes at some multiple of 60 seconds, so this can take several minutes.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (cantBeChecked > 1 && !found) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Text(
                "More than one transmitter accepts a connection without responding. Scan the code on the applicator or box, " +
                    "or type its serial number, to pick out its transmitter, or move the others away and watch which one stops being seen.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }

    if (found) {
        Separator(if (mine.size == 1) "This is $targetLabel" else "These use the code ${state.sensorCode}")
        mine.forEach { TransmitterCard(it, idLabel, now, state.sensorCode != null, scanned = true) }
    }
    // Once the transmitter being looked for is found, the others are tucked away.
    var showOthers by remember { mutableStateOf(false) }
    if (found && others.isNotEmpty()) {
        TextButton(onClick = { showOthers = !showOthers }) {
            Text(if (showOthers) "Hide other G7s nearby" else "Show other G7s nearby (${others.size})")
        }
    }
    if (!found || showOthers) {
        others.groupBy { it.status }.forEach { (status, cards) ->
            Separator("${status.label} (${cards.size})")
            cards.forEach { TransmitterCard(it, idLabel, now, state.sensorCode != null) }
        }
    }

    StopButton(onStop)
}

/** Titled divider above each group of cards. */
@Composable
private fun Separator(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

private val TransmitterStatus.order: Int
    get() = when (this) {
        TransmitterStatus.CantBeChecked -> 0
        TransmitterStatus.Checking -> 1
        TransmitterStatus.WaitingToConnect -> 2
        TransmitterStatus.TooFaint -> 3
        TransmitterStatus.CouldNotSort -> 4
        TransmitterStatus.Working -> 5
    }

private val TransmitterStatus.label: String
    get() = when (this) {
        TransmitterStatus.WaitingToConnect -> "Heard: waiting for it to wake"
        TransmitterStatus.TooFaint -> "Too faint to connect"
        TransmitterStatus.Checking -> "Connected: seeing if it responds…"
        TransmitterStatus.Working -> "Responds (working sensor): left alone"
        TransmitterStatus.CantBeChecked -> "Accepts a connection, doesn't respond"
        TransmitterStatus.CouldNotSort -> "Couldn't test whether it responds on this phone: left alone"
    }

@Composable
private fun TransmitterCard(t: Transmitter, idLabel: String, now: Instant, codeSet: Boolean, scanned: Boolean = false) {
    val main = t.status == TransmitterStatus.CantBeChecked
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = when {
            scanned -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            main -> CardDefaults.cardColors()
            else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t.name ?: "(no name)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    t.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (main) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (main) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Text("$idLabel: ${t.id}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (codeSet) {
                Text(
                    codeLine(t),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (t.codeStatus == CodeStatus.Matches) FontWeight.Bold else FontWeight.Normal,
                )
            }

            val seconds = (now - t.lastSeenAt).inWholeSeconds.coerceAtLeast(0)
            val signal = t.lastSeenRssi?.let { "$it dBm" } ?: "not measured (seen by connecting)"
            if (main || scanned) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Time since last seen (min:sec)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        formatMinutesSeconds(seconds),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    )
                    Text("Last seen: ${t.lastSeenAt.toLocalDateTimeString()}", style = MaterialTheme.typography.bodyLarge)
                    // Between connections while G7timing keeps connecting; otherwise between the wake-ups the scan hears.
                    val interval = (if (main) t.lastInterval else t.lastWakeInterval)?.let { formatMinutesSeconds(it.inWholeSeconds) } ?: "—"
                    Text(
                        "Last interval: $interval · Signal: ${t.lastSeenRssi?.let { "$it dBm" } ?: "not measured"}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (t.status) {
                            TransmitterStatus.Working ->
                                if (codeSet) "Working sensor: timed from its advertisements"
                                else "Working sensor: not connected to again, timed from its advertisements"
                            TransmitterStatus.CouldNotSort -> "Not connected to again, timed from its advertisements"
                            else -> "Last connection: ${t.lastConnectionAt?.toLocalDateTimeString() ?: "—"} · connections: ${t.connectionCount}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Last seen: ${t.lastSeenAt.toLocalDateTimeString()} · $signal · ${formatMinutesSeconds(seconds)} ago",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                // Not reconnected, so the interval comes from the wake-ups the scan hears.
                Text(
                    "Last interval: " + (t.lastWakeInterval?.let { formatMinutesSeconds(it.inWholeSeconds) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** What a card says about the sensor code being looked for. */
private fun codeLine(t: Transmitter): String = when (t.codeStatus) {
    CodeStatus.Matches -> "Uses the sensor code"
    CodeStatus.DoesNotMatch -> "Doesn't use the sensor code"
    CodeStatus.Checking -> "Checking its sensor code…"
    CodeStatus.GaveUp -> "Its code couldn't be checked (no answer in ${t.codeAttempts} tries)"
    CodeStatus.NotChecked -> when (t.status) {
        TransmitterStatus.CantBeChecked -> "Doesn't respond, so its code can't be checked"
        TransmitterStatus.TooFaint -> "Too faint to check its code"
        TransmitterStatus.CouldNotSort -> "Its code can't be checked on this phone"
        else -> if (t.codeAttempts > 0) "Code not checked yet (try ${t.codeAttempts} gave no answer)" else "Code not checked yet"
    }
}

/** Current time, refreshed on each whole-second boundary, for live "time since" counters. */
@Composable
private fun rememberNow(): State<Instant> = produceState(Clock.System.now()) {
    while (true) {
        value = Clock.System.now()
        delay(1000 - (value.toEpochMilliseconds() % 1000))
    }
}

@Composable
private fun EventLog(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider()
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide event log" else "Show event log (${lines.size})") }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.asReversed().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
