package app.tfdn.lirelle.feature.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.feature.player.controller.PlayerUiState

internal const val SleepSheetTag = "player-sleep-sheet"

/** The choices of the sheet, in their own order: the end of the chapter, then the durations. */
private val Choices: List<SleepChoice> =
    listOf(SleepChoice.EndOfChapter) + listOf(5, 10, 15, 30, 45, 60).map(SleepChoice::Minutes)

private val ChoiceMinHeight = 52.dp
private val CheckSize = 20.dp
private val TouchTarget = 48.dp

/**
 * How the timer's stop at the end of the current part is said: [endOf] as a choice ("End of chapter"), [stopsAtEnd]
 * while it runs ("Stops at the end of the chapter"). A narration's parts are its sections.
 */
internal data class SleepNames(@StringRes val endOf: Int, @StringRes val stopsAtEnd: Int)

/** An audiobook's parts: its chapters. */
internal val ChapterSleep = SleepNames(R.string.player_sleep_end_of_chapter, R.string.player_sleep_stops_chapter)

/** The choices as the sheet shows them: the last one chosen first, the others in their order. */
internal fun sleepChoices(last: SleepChoice?): List<SleepChoice> =
    if (last == null) Choices else listOf(last) + (Choices - last)

/**
 * The sleep timer (spec 5.5): when it stops, the choices with the active one checked, shaking to
 * extend it, and "Stop the timer" while it runs. A choice or "Stop the timer" closes the sheet. [names] says
 * what the end of the current part is (a chapter's, or a narration's section's).
 */
@Composable
internal fun SleepSheet(
    state: PlayerUiState,
    controls: SleepControls,
    settings: PlayerSettingsActions,
    onDismiss: () -> Unit,
    names: SleepNames = ChapterSleep,
) {
    GripSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
                .testTag(SleepSheetTag),
        ) {
            SleepHeader(state, names)
            sleepChoices(state.settings.lastSleepChoice).forEach { choice ->
                ChoiceRow(choice, names, chosen = state.sleep?.choice == choice) {
                    controls.startSleep(choice)
                    onDismiss()
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            ShakeRow(state.settings.shakeToExtend, settings::setShakeToExtend)
            if (state.sleep != null) {
                OutlinedButton(
                    onClick = {
                        controls.cancelSleep()
                        onDismiss()
                    },
                    modifier = Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget),
                ) {
                    SingleLineLabel(stringResource(R.string.player_sleep_stop))
                }
            }
        }
    }
}

/** "Sleep timer", and when it stops while it runs. */
@Composable
private fun SleepHeader(state: PlayerUiState, names: SleepNames) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.player_sleep_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        val sleep = state.sleep ?: return@Column
        // No live region: a screen reader would say it again each minute.
        val remaining = sleep.remainingMs
        Text(
            text = if (remaining != null) {
                stringResource(R.string.player_sleep_stops_in, durationText(remaining))
            } else {
                stringResource(names.stopsAtEnd)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChoiceRow(choice: SleepChoice, names: SleepNames, chosen: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (chosen) colours.surfaceContainerHigh else Color.Transparent)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = ChoiceMinHeight)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = choiceText(choice, names),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (chosen) colours.primary else colours.onSurface,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
        )
        Box(Modifier.size(CheckSize)) {
            if (chosen) Icon(Icons.Rounded.Check, contentDescription = null, tint = colours.primary)
        }
    }
}

@Composable
private fun choiceText(choice: SleepChoice, names: SleepNames): String = when (choice) {
    SleepChoice.EndOfChapter -> stringResource(names.endOf)
    is SleepChoice.Minutes -> pluralStringResource(R.plurals.player_sleep_minutes, choice.minutes, choice.minutes)
}

/** "Shake to extend", during the last minute; the whole row turns it off and on. */
@Composable
private fun ShakeRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.player_sleep_shake), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.player_sleep_shake_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}
