package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ui.GripSheet

private val SheetMaxWidth = 640.dp
private val Gutter = 16.dp
private val SwitchMinHeight = 48.dp

/**
 * The reading settings, in a sheet over the bottom of the page, in the reader's dark look: the fit, two pages
 * side by side, the background, for an album that can be read panel by panel what that takes from the device,
 * and the reading direction. Each choice is taken at once, the page changing behind the sheet.
 *
 * @param onZones the switch that draws the zones of the pages was turned; null in a build that cannot be
 *   debugged, which has no such switch.
 */
@Composable
internal fun ComicSettingsSheet(
    state: ComicReaderUiState,
    actions: ComicSettingsActions,
    onDismiss: () -> Unit,
    onZones: ((Boolean) -> Unit)? = null,
) {
    GripSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = SheetMaxWidth)
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = Gutter, end = Gutter, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.comics_settings),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            FitGroup(state.settings.fit, actions::setFit)
            TwoPagesGroup(state.settings.twoPages, actions::setTwoPages)
            BackgroundGroup(state.settings.background, actions::setBackground)
            if (state.panels.offered) PanelsGroup(state.settings.wholePageFirst, actions::setWholePageFirst)
            DirectionGroup(state, actions::setDirection)
            if (onZones != null) ZonesSwitch(state.zonesShown, onZones)
        }
    }
}

/** Draws the zones of the pages over them, in a build that can be debugged. The whole row is the switch. */
@Composable
private fun ZonesSwitch(shown: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .toggleable(value = shown, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = SwitchMinHeight),
        horizontalArrangement = Arrangement.spacedBy(Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.comics_debug_zones),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = shown, onCheckedChange = null)
    }
}

/** The direction, and what it is remembered for: the series, named, or the album alone. */
@Composable
private fun DirectionGroup(state: ComicReaderUiState, onSelect: (Direction) -> Unit) {
    SettingGroup(stringResource(R.string.comics_settings_direction)) {
        val choices = listOf(
            Direction.LeftToRight to stringResource(R.string.comics_direction_left_to_right),
            Direction.RightToLeft to stringResource(R.string.comics_direction_right_to_left),
        )
        SegmentedChoice(choices, state.settings.direction, onSelect)
        val series = state.series?.name?.takeIf { state.directionScope is DirectionScope.Series }
        Note(
            series?.let { stringResource(R.string.comics_direction_for_series, it) }
                ?: stringResource(R.string.comics_direction_for_album),
        )
    }
}
