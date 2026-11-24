package app.tfdn.lirelle.feature.player.narration

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.SkipButton
import app.tfdn.lirelle.feature.player.clockTime
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.timeLeft
import app.tfdn.lirelle.feature.player.speedText

private val BarRowMinHeight = 60.dp
private val WideContentMax = 880.dp

/** Below this width a wide bar has no room for its skips, speed and timer beside the section: it is the phone's. */
private val WideBarMinWidth = 600.dp
private val BarPlaySize = 48.dp
private val WidePlaySize = 52.dp
private val PlayIconSize = 26.dp
private val TouchTarget = 48.dp
private val WaveSize = 16.dp

/**
 * The narration's bar at the bottom of the reader: the section's progress, the wave and the section's title, its
 * time played, time left and the speed, and the previous sentence, play or pause, the next sentence. A tap on the
 * texts opens the panel ([onOpenPanel]). [wide] (a tablet) adds the skips, the speed and the sleep timer, which open
 * their sheets, the content centred; a window too narrow for them keeps the phone's bar.
 *
 * The bar does not keep clear of the navigation bar: the caller adds the system gesture inset below it.
 */
@Composable
fun NarrationBar(
    state: NarrationUi,
    colors: NarrationColors,
    actions: NarrationActions,
    onOpenPanel: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    var sheet by rememberSaveable { mutableStateOf<NarrationSheet?>(null) }
    NarrationTheme(colors) {
        BarFrame(colors, sectionFraction(state), modifier) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (wide && maxWidth >= WideBarMinWidth) {
                    WideBarRow(state, colors, actions, onOpenPanel, onSheet = { sheet = it })
                } else {
                    PhoneBarRow(state, colors, actions, onOpenPanel)
                }
            }
        }
        NarrationSheets(sheet, state, actions, onDismiss = { sheet = null })
    }
}

@Composable
private fun PhoneBarRow(state: NarrationUi, colors: NarrationColors, actions: NarrationActions, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BarRowMinHeight)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarTexts(state, colors, onOpen, Modifier.weight(1f).padding(start = 8.dp))
        SentenceButtons(state, actions, BarPlaySize)
    }
}

@Composable
private fun WideBarRow(
    state: NarrationUi,
    colors: NarrationColors,
    actions: NarrationActions,
    onOpen: () -> Unit,
    onSheet: (NarrationSheet) -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = WideContentMax)
            .fillMaxWidth()
            .heightIn(min = BarRowMinHeight)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarTexts(state, colors, onOpen, Modifier.weight(1f))
        SkipButton(state.settings.backSeconds, R.string.player_button_back, mirrored = false, actions::back)
        SentenceButtons(state, actions, WidePlaySize)
        SkipButton(state.settings.forwardSeconds, R.string.player_button_forward, mirrored = true, actions::forward)
        Spacer(Modifier.width(12.dp))
        SpeedButton(state.speed, colors) { onSheet(NarrationSheet.Speed) }
        // While a timer runs, the moon is in the accent and a screen reader hears the time left.
        val sleepLeft = narrationSleepLeft(state)
        IconButton(
            onClick = { onSheet(NarrationSheet.Sleep) },
            modifier = Modifier
                .size(TouchTarget)
                .semantics { sleepLeft?.let { stateDescription = it } },
        ) {
            Icon(
                imageVector = Icons.Rounded.Bedtime,
                contentDescription = stringResource(R.string.player_sleep_title),
                tint = if (state.sleep != null) colors.accent else colors.foreground,
            )
        }
    }
}

/**
 * The wave and the section's title (one line, cut short), then its times and the speed, which go to the next line
 * part by part rather than be cut; a tap opens the panel, which a screen reader hears after the texts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarTexts(state: NarrationUi, colors: NarrationColors, onOpen: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                onClickLabel = stringResource(R.string.narration_controls),
                role = Role.Button,
                onClick = onOpen,
            )
            .heightIn(min = TouchTarget)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null, Modifier.size(WaveSize), tint = colors.accent)
            Text(
                text = state.sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            barTimesParts(state).forEach { part ->
                Text(part, style = style, color = colors.muted, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The previous sentence, play or pause (a circle of [playSize] in the accent), the next sentence. */
@Composable
internal fun SentenceButtons(state: NarrationUi, actions: NarrationActions, playSize: Dp) {
    SentenceButton(Icons.Rounded.SkipPrevious, R.string.narration_previous_sentence, actions::previousSentence)
    FilledIconButton(onClick = actions::playPause, modifier = Modifier.size(playSize)) {
        Icon(
            imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
            modifier = Modifier.size(PlayIconSize),
        )
    }
    SentenceButton(Icons.Rounded.SkipNext, R.string.narration_next_sentence, actions::nextSentence)
}

@Composable
internal fun SentenceButton(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(TouchTarget)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The speed, outlined; a screen reader hears what it is with its value. */
@Composable
private fun SpeedButton(speed: Float, colors: NarrationColors, onClick: () -> Unit) {
    val value = speedText(speed)
    val spoken = stringResource(R.string.player_joined, stringResource(R.string.player_speed), value)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = spoken },
        border = BorderStroke(1.dp, colors.outline),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        SingleLineLabel(value, color = colors.foreground)
    }
}

/** "12:04 ·", "−23:51 ·", "1.25×": the time played in the section, the time left in it at the speed, the speed. */
@Composable
private fun barTimesParts(state: NarrationUi): List<String> {
    val left = timeLeft(state.sectionDurationMs, state.elapsedMs, state.speed)
    return listOf(
        stringResource(R.string.narration_part_then, clockTime(state.elapsedMs)),
        stringResource(R.string.narration_part_then, stringResource(R.string.player_remaining, clockTime(left))),
        speedText(state.speed),
    )
}

/** How far into its section the narration is, from 0 to 1. */
private fun sectionFraction(state: NarrationUi): Float =
    if (state.sectionDurationMs > 0) (state.elapsedMs.toFloat() / state.sectionDurationMs).coerceIn(0f, 1f) else 0f
