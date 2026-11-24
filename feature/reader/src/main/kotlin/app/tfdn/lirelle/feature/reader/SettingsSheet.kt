package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.BrightnessRange
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningSlot

/** Between the values of a summary. */
private const val SummarySeparator = " · "

/**
 * The reader's sheets over the page: the reading settings, and the two it leads to. Nothing is
 * shown for the table of contents and the search, which are not among them, nor when no sheet is open.
 *
 * Give it the whole of the reader's area: a sheet takes the bottom of it and leaves the rest to
 * the page. The system's back is the caller's, through the view model.
 */
@Composable
fun ReaderSheets(state: ReaderUiState, actions: ReaderActions, modifier: Modifier = Modifier) {
    val sheet = state.openSheet
    if (sheet == null || sheet == ReaderSheet.Contents || sheet == ReaderSheet.Search) return
    ReaderChromeTheme(state.colours) {
        when (sheet) {
            ReaderSheet.RunningLines -> RunningLineSettings(state, actions, modifier)
            ReaderSheet.MoreOptions ->
                MoreOptions(state.settings, actions, modifier, cornerBookmark = state.capabilities.annotations)
            else -> SettingsSheet(state.settings, state.systemBrightness, actions, modifier)
        }
    }
}

/** @param systemBrightness the level of the screen while the reader follows the system. */
@Composable
private fun SettingsSheet(
    settings: ReaderSettings,
    systemBrightness: Float,
    actions: ReaderActions,
    modifier: Modifier,
) {
    val title = stringResource(R.string.reader_settings_title)
    SheetFrame(title, onDone = actions::dismissSheet, onBack = null, modifier) {
        TextSettings(settings, actions::updateSettings)
        Brightness(settings.brightness, systemBrightness, actions)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ValueRow(
            label = stringResource(R.string.reader_settings_running_lines),
            value = runningLinesSummary(settings),
            onClick = { actions.showSheet(ReaderSheet.RunningLines) },
        )
        ValueRow(
            label = stringResource(R.string.reader_settings_more),
            value = moreOptionsSummary(settings),
            onClick = { actions.showSheet(ReaderSheet.MoreOptions) },
        )
    }
}

/**
 * The brightness of the window: the system's, or the slider's. While the system's is followed the
 * slider stands at the level of the screen, so that taking over, with the switch or by moving the
 * slider, starts from what the user sees. The screen follows the slider at once; the level is
 * stored when the slider is let go.
 */
@Composable
private fun Brightness(brightness: Float?, systemBrightness: Float, actions: ReaderActions) {
    val start = systemBrightness.coerceIn(BrightnessRange)
    val name = stringResource(R.string.reader_settings_brightness)
    SectionLabel(name)
    SwitchRow(
        label = stringResource(R.string.reader_settings_brightness_system),
        checked = brightness == null,
        onChange = { follow ->
            actions.updateSettings {
                it.copy(brightness = if (follow) null else it.brightness ?: start)
            }
        },
    )
    SlimSlider(
        value = brightness ?: start,
        onValueChange = actions::setBrightness,
        onValueChangeFinished = actions::keepBrightness,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = name },
        valueRange = BrightnessRange,
    )
}

/** What the header and the footer show, in reading order; "Nothing" when they show nothing. */
@Composable
private fun runningLinesSummary(settings: ReaderSettings): String {
    val shown = listOf(settings.header.left, settings.header.right, settings.footer.left, settings.footer.right)
        .filter { it != RunningSlot.Nothing }
        .distinct()
        .map { stringResource(it.label) }
    return if (shown.isEmpty()) {
        stringResource(R.string.reader_slot_nothing)
    } else {
        shown.joinToString(SummarySeparator)
    }
}

/** The layout, then what is switched on among the volume keys and the brightness gesture. */
@Composable
private fun moreOptionsSummary(settings: ReaderSettings): String = listOfNotNull(
    stringResource(settings.flow.label),
    if (settings.volumeKeys) stringResource(R.string.reader_more_summary_volume_keys) else null,
    when (settings.brightnessEdge) {
        BrightnessEdge.Left -> stringResource(R.string.reader_more_summary_edge_left)
        BrightnessEdge.Right -> stringResource(R.string.reader_more_summary_edge_right)
        BrightnessEdge.Off -> null
    },
).joinToString(SummarySeparator)
