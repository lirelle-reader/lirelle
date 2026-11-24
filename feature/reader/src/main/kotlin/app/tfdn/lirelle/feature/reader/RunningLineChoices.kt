package app.tfdn.lirelle.feature.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningSlot

/** One of the four places where something can be shown above or below the page. */
private enum class RunningEnd(@StringRes val label: Int) {
    HeaderLeft(R.string.reader_running_header_left),
    HeaderRight(R.string.reader_running_header_right),
    FooterLeft(R.string.reader_running_footer_left),
    FooterRight(R.string.reader_running_footer_right),
}

private fun ReaderSettings.slot(end: RunningEnd): RunningSlot = when (end) {
    RunningEnd.HeaderLeft -> header.left
    RunningEnd.HeaderRight -> header.right
    RunningEnd.FooterLeft -> footer.left
    RunningEnd.FooterRight -> footer.right
}

private fun ReaderSettings.with(end: RunningEnd, slot: RunningSlot): ReaderSettings = when (end) {
    RunningEnd.HeaderLeft -> copy(header = header.copy(left = slot))
    RunningEnd.HeaderRight -> copy(header = header.copy(right = slot))
    RunningEnd.FooterLeft -> copy(footer = footer.copy(left = slot))
    RunningEnd.FooterRight -> copy(footer = footer.copy(right = slot))
}

/**
 * What the header and the footer show at each of their ends: four choices. A choice opens in place, one at a
 * time, on the eight things it can be. The reader's sheet and the Reading page of the settings both show it.
 *
 * @param columns how many choices sit on one line: one in a sheet, two where there is room, the left end
 *   beside the right one.
 */
@Composable
internal fun RunningLineChoices(
    settings: ReaderSettings,
    change: SettingsChange,
    columns: Int = 1,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf<RunningEnd?>(null) }
    val choice: @Composable (RunningEnd, Modifier) -> Unit = { end, cell ->
        RunningChoice(
            end = end,
            slot = settings.slot(end),
            expanded = open == end,
            onToggle = { open = if (open == end) null else end },
            onSelect = { slot ->
                change { it.with(end, slot) }
                open = null
            },
            modifier = cell,
        )
    }
    Column(modifier) {
        RunningEnd.entries.chunked(columns).forEach { line ->
            if (line.size == 1) {
                choice(line.single(), Modifier)
            } else {
                Row { line.forEach { end -> choice(end, Modifier.weight(1f)) } }
            }
        }
    }
}

/** One end of a line: its name over what it shows, and under it, once opened, what it can show. */
@Composable
private fun RunningChoice(
    end: RunningEnd,
    slot: RunningSlot,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (RunningSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ValueRow(
            label = stringResource(end.label),
            value = stringResource(slot.label),
            onClick = onToggle,
            trailing = {
                Icon(SheetIcons.Expand, contentDescription = null, Modifier.rotate(if (expanded) 180f else 0f))
            },
        )
        if (expanded) SlotChoices(slot, onSelect)
    }
}

@Composable
private fun SlotChoices(selected: RunningSlot, onSelect: (RunningSlot) -> Unit) {
    Column(Modifier.selectableGroup().padding(start = 16.dp)) {
        RunningSlot.entries.forEach { slot ->
            RadioRow(stringResource(slot.label), selected = slot == selected, onSelect = { onSelect(slot) })
        }
    }
}
