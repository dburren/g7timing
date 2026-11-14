// SPDX-FileCopyrightText: 2026 David Burren
// SPDX-License-Identifier: Apache-2.0

package com.bionicwookiee.g7timing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.bionicwookiee.g7timing.ble.BleAvailability
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
                        "Hold the G7 applicator against the back of this phone, then press Start. G7timing lists every " +
                            "G7 transmitter it sees, and shows each time one that accepts a Bluetooth connection but " +
                            "doesn't respond is seen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = controller::start,
                        enabled = availability == BleAvailability.Ready,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start") }
                } else {
                    Session(state, controller.identifierLabel, onStop = controller::stop)
                    EventLog(state.log)
                }

                Text(
                    "G7timing does not read glucose and is not a medical device. It never pairs (bonds) with a sensor " +
                        "and never signs in to one. It connects once to each G7 nearby (including working sensors) to " +
                        "see whether it responds. It can't tell which applicator a transmitter belongs to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Column {
            Text(
                when {
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
    if (cantBeChecked == 0) {
        Text(
            "Keep the applicator against the phone. A quiet G7 wakes at some multiple of 60 seconds, so this can take " +
                "several minutes.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (cantBeChecked > 1) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Text(
                "More than one transmitter accepts a connection without responding. G7timing can't tell which applicator each one belongs to: " +
                    "move the others away and watch which one stops being seen.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }

    list.groupBy { it.status }.forEach { (status, cards) ->
        StatusSeparator(status, cards.size)
        cards.forEach { TransmitterCard(it, idLabel, now) }
    }

    StopButton(onStop)
}

/** Titled divider above each group of cards that share a status. */
@Composable
private fun StatusSeparator(status: TransmitterStatus, count: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${status.label} ($count)",
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
private fun TransmitterCard(t: Transmitter, idLabel: String, now: Instant) {
    val main = t.status == TransmitterStatus.CantBeChecked
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (main) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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

            val seconds = (now - t.lastSeenAt).inWholeSeconds.coerceAtLeast(0)
            val signal = t.lastSeenRssi?.let { "$it dBm" } ?: "not measured (seen by connecting)"
            if (main) {
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
                    val interval = t.lastInterval?.let { formatMinutesSeconds(it.inWholeSeconds) } ?: "—"
                    Text(
                        "Last interval: $interval · Signal: ${t.lastSeenRssi?.let { "$it dBm" } ?: "not measured"}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Last connection: ${t.lastConnectionAt?.toLocalDateTimeString() ?: "—"} · connections: ${t.connectionCount}",
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
