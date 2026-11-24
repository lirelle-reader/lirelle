package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderSettings

/**
 * The layout and the gestures: pages or scrolling, the volume keys, the footnotes' panel, the corner's
 * bookmark, the brightness gesture. The reader's sheet and the Reading page of the settings both show it.
 *
 * @param cornerBookmark false for a source without annotations: no bookmark, so no corner's switch.
 */
@Composable
internal fun LayoutSettings(
    settings: ReaderSettings,
    change: SettingsChange,
    modifier: Modifier = Modifier,
    cornerBookmark: Boolean = true,
) {
    Column(modifier) {
        SectionLabel(stringResource(R.string.reader_more_layout))
        Column(Modifier.selectableGroup()) {
            ReaderFlow.entries.forEach { flow ->
                RadioRow(
                    label = stringResource(flow.label),
                    selected = settings.flow == flow,
                    onSelect = { change { it.copy(flow = flow) } },
                )
            }
        }
        SwitchRow(
            label = stringResource(R.string.reader_more_volume_keys),
            checked = settings.volumeKeys,
            onChange = { on -> change { it.copy(volumeKeys = on) } },
            hint = stringResource(R.string.reader_more_volume_keys_hint),
        )
        SwitchRow(
            label = stringResource(R.string.reader_more_footnote_panel),
            checked = settings.footnotePanel,
            onChange = { on -> change { it.copy(footnotePanel = on) } },
        )
        if (cornerBookmark) {
            SwitchRow(
                label = stringResource(R.string.reader_more_corner_bookmark),
                checked = settings.cornerBookmark,
                onChange = { on -> change { it.copy(cornerBookmark = on) } },
            )
        }
        SectionLabel(stringResource(R.string.reader_more_brightness_gesture))
        Text(
            text = stringResource(R.string.reader_more_brightness_gesture_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(Modifier.selectableGroup()) {
            BrightnessEdge.entries.forEach { edge ->
                RadioRow(
                    label = stringResource(edge.label),
                    selected = settings.brightnessEdge == edge,
                    onSelect = { change { it.copy(brightnessEdge = edge) } },
                )
            }
        }
    }
}
