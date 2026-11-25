package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.settings.MaxSpeed
import app.tfdn.lirelle.core.audio.settings.MinSpeed
import app.tfdn.lirelle.core.audio.settings.SpeedStep
import app.tfdn.lirelle.core.audio.settings.toValidSpeed
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.controller.timeLeft
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

internal const val SpeedSheetTag = "player-speed-sheet"
internal const val SpeedSliderTag = "player-speed-slider"

private val StepButtonSize = 52.dp

/** How long a speed asked is shown without the session saying it. */
private val PendingAtMost = 2.seconds
private val ShortcutMinWidth = 64.dp
private val ShortcutHeight = 44.dp

/**
 * The speed (spec 5.4): the value large, minus and plus by a step, a slider from 0.5× to 3× that sets
 * the speed when the finger is lifted, the shortcuts, and the time left at that speed. The speed is
 * the book's.
 */
@Composable
internal fun SpeedSheet(state: PlayerUiState, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    GripSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        SpeedContent(state, onSpeed, Modifier.testTag(SpeedSheetTag))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedContent(state: PlayerUiState, onSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    // A speed asked and not yet the session's: a second tap steps from it, not from the old one.
    var pending by remember { mutableStateOf<Float?>(null) }
    // The session said its speed, whatever it is (a request may be clamped or ignored), or it said nothing for a while.
    LaunchedEffect(state.speed) { pending = null }
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        delay(PendingAtMost)
        pending = null
    }
    val speed = dragged ?: pending ?: state.speed
    val choose = { chosen: Float ->
        pending = chosen
        onSpeed(chosen)
    }
    val spoken = speedText(speed)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_speed),
                modifier = Modifier
                    .padding(end = 12.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.player_speed_for_book),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Stepper(speed, choose)
        Column {
            SlimSlider(
                value = speed,
                onValueChange = { dragged = it.toValidSpeed() },
                onValueChangeFinished = {
                    dragged?.let(choose)
                    dragged = null
                },
                valueRange = MinSpeed..MaxSpeed,
                steps = ((MaxSpeed - MinSpeed) / SpeedStep).roundToInt() - 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SpeedSliderTag)
                    .semantics { stateDescription = spoken },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val ends = MaterialTheme.typography.labelMedium
                Text(speedText(MinSpeed), style = ends, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(speedText(MaxSpeed), style = ends, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedShortcuts.forEach { shortcut ->
                Shortcut(shortcut, selected = abs(shortcut - speed) < SameSpeed, onClick = { choose(shortcut) })
            }
        }
        timeLeftAt(state, speed)?.let {
            Text(
                text = stringResource(R.string.player_speed_time_left, durationText(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The book's time left at [speed]; null when its length is not known. */
private fun timeLeftAt(state: PlayerUiState, speed: Float): Long? =
    state.totalMs?.let { timeLeft(it, state.bookTimeMs, speed) }

/** Minus, the value, plus; the value takes the lines it needs between the buttons, which keep their size. */
@Composable
private fun Stepper(speed: Float, onSpeed: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedIconButton(
            onClick = { onSpeed((speed - SpeedStep).toValidSpeed()) },
            enabled = speed > MinSpeed + SameSpeed,
            modifier = Modifier.size(StepButtonSize),
        ) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.player_speed_slower))
        }
        Text(
            text = speedText(speed),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
        )
        OutlinedIconButton(
            onClick = { onSpeed((speed + SpeedStep).toValidSpeed()) },
            enabled = speed < MaxSpeed - SameSpeed,
            modifier = Modifier.size(StepButtonSize),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.player_speed_faster))
        }
    }
}

@Composable
private fun Shortcut(speed: Float, selected: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = ShortcutMinWidth)
            .heightIn(min = ShortcutHeight)
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) colours.primary else colours.surfaceContainer,
        contentColor = if (selected) colours.onPrimary else colours.onSurface,
        border = if (selected) null else BorderStroke(1.dp, colours.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleLineLabel(speedText(speed))
        }
    }
}
