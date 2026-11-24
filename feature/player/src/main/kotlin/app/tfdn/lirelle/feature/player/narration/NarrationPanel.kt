package app.tfdn.lirelle.feature.player.narration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.feature.player.BarPlace
import app.tfdn.lirelle.feature.player.BottomRow
import app.tfdn.lirelle.feature.player.ChapterBar
import app.tfdn.lirelle.feature.player.GoBackButton
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.RoundButton
import app.tfdn.lirelle.feature.player.SkipButton
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import kotlin.math.roundToInt

internal const val NarrationPanelTag = "narration-panel"

/** The sheets the narration's panel and its wide bar open. */
internal enum class NarrationSheet { Speed, Sleep, Sections }

private val TitleWaveSize = 20.dp
private val StopIconSize = 18.dp
private val TouchTarget = 48.dp

/**
 * The narration's panel, a sheet in the reader theme's colours ([colors]): the section, where it is in the
 * narration, "Go back" after a section was chosen, its bar (a drag seeks in the section when the finger is lifted),
 * the skips and the sentence buttons around play or pause, the speed, sleep timer and sections tiles, which open
 * their sheets, and "Stop listening".
 * Its grip, a tap above it and back call [onDismiss]; its content scrolls on its own.
 */
@Composable
fun NarrationPanel(state: NarrationUi, colors: NarrationColors, actions: NarrationActions, onDismiss: () -> Unit) {
    var sheet by rememberSaveable { mutableStateOf<NarrationSheet?>(null) }
    NarrationTheme(colors) {
        GripSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
                    .testTag(NarrationPanelTag),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PanelHeader(state, colors)
                // After a section chosen, as the 3a player offers it after a chapter.
                if (state.goBackShown) {
                    GoBackButton(onGoBack = actions::goBack, modifier = Modifier.padding(top = 12.dp))
                }
                ChapterBar(
                    place = BarPlace(
                        chapterIndex = (state.sectionNumber - 1).coerceAtLeast(0),
                        elapsedMs = state.elapsedMs,
                        durationMs = state.sectionDurationMs.takeIf { it > 0 },
                        speed = state.speed,
                    ),
                    onSeek = actions::seekInSection,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    label = R.string.narration_section_bar,
                )
                PanelTransport(state, actions)
                BottomRow(
                    speed = state.speed,
                    sleepLeft = narrationSleepLeft(state),
                    sleeping = state.sleep != null,
                    onSpeed = { sheet = NarrationSheet.Speed },
                    onSleep = { sheet = NarrationSheet.Sleep },
                    onChapters = { sheet = NarrationSheet.Sections },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    chaptersName = R.string.narration_sections,
                )
                StopButton {
                    actions.stop()
                    onDismiss()
                }
            }
        }
        NarrationSheets(sheet, state, actions, onDismiss = { sheet = null })
    }
}

/** The wave and the section's title, on the lines it needs, then "Section 12 of 48 · 37% of the book". */
@Composable
private fun PanelHeader(state: NarrationUi, colors: NarrationColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(TitleWaveSize), tint = colors.accent)
            Text(
                text = state.sectionTitle,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = colors.foreground,
            )
        }
        Text(
            text = stringResource(
                R.string.narration_section_line,
                state.sectionNumber,
                state.sectionCount,
                state.bookPercent.roundToInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
    }
}

/** Back, the previous sentence, play or pause large, the next sentence, forward. */
@Composable
private fun PanelTransport(state: NarrationUi, actions: NarrationActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(state.settings.backSeconds, R.string.player_button_back, mirrored = false, actions::back)
        SentenceButton(Icons.Rounded.SkipPrevious, R.string.narration_previous_sentence, actions::previousSentence)
        RoundButton(
            icon = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            label = stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
            onClick = actions::playPause,
        )
        SentenceButton(Icons.Rounded.SkipNext, R.string.narration_next_sentence, actions::nextSentence)
        SkipButton(state.settings.forwardSeconds, R.string.player_button_forward, mirrored = true, actions::forward)
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(top = 12.dp, start = 16.dp, end = 16.dp)
            .heightIn(min = TouchTarget),
    ) {
        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(StopIconSize))
        SingleLineLabel(stringResource(R.string.player_stop_listening), Modifier.padding(start = 8.dp))
    }
}
