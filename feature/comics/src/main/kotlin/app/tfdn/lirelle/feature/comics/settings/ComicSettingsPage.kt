package app.tfdn.lirelle.feature.comics.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.ui.SettingsPageFrame
import app.tfdn.lirelle.feature.comics.BackgroundGroup
import app.tfdn.lirelle.feature.comics.FitGroup
import app.tfdn.lirelle.feature.comics.PanelsGroup
import app.tfdn.lirelle.feature.comics.R
import app.tfdn.lirelle.feature.comics.TwoPagesGroup

private val Gutter = 16.dp

/** @param onBack null inside a pane: the page then shows its title and no arrow. */
@Composable
fun ComicSettingsRoute(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ComicSettingsViewModel>()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    ComicSettingsPage(settings = settings, changes = viewModel, onBack = onBack, modifier = modifier)
}

/**
 * The comics settings, in the app's theme: the fit, two pages side by side, the background and what reading
 * panel by panel takes from the device, as in the reader's sheet, then where the reading direction is set, which
 * is not here as it belongs to a series or to an album. Each choice is taken at once.
 *
 * @param settings what is stored; null until it is read, nothing showing under the title before, so that no
 *   default is marked for a moment.
 * @param onBack null inside a pane: the page then shows its title and no arrow.
 */
@Composable
internal fun ComicSettingsPage(
    settings: ComicSettings?,
    changes: ComicSettingsChanges,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsPageFrame(
        title = stringResource(R.string.comics_settings_page_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (settings != null) {
            Column(Modifier.padding(horizontal = Gutter)) {
                FitGroup(settings.fit, changes::setFit)
                TwoPagesGroup(settings.twoPages, changes::setTwoPages)
                BackgroundGroup(settings.background, changes::setBackground)
                PanelsGroup(settings.wholePageFirst, changes::setWholePageFirst)
                DirectionNote(Modifier.padding(top = 24.dp))
            }
        }
    }
}

/** Where the reading direction is set, in a box apart from the settings: there is nothing to choose here. */
@Composable
private fun DirectionNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.comics_settings_direction),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.comics_direction_where),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
