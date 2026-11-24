package app.tfdn.lirelle.feature.settings.appearance

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ui.SettingsPageFrame
import app.tfdn.lirelle.feature.settings.NoteBox
import app.tfdn.lirelle.feature.settings.R

/** @param onBack null inside a pane: the page then shows its title and no arrow. */
@Composable
fun AppearanceRoute(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<AppearanceViewModel>()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    AppearancePage(theme = theme, onChoose = viewModel::choose, onBack = onBack, modifier = modifier)
}

/**
 * The app's theme: three tiles to choose from, what the chosen one means, and what it changes for the reading
 * theme.
 *
 * @param theme the theme chosen; null until it is known, nothing being marked before.
 * @param onBack null inside a pane: the page then shows its title and no arrow.
 */
@Composable
internal fun AppearancePage(
    theme: AppTheme?,
    onChoose: (AppTheme) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsPageFrame(title = stringResource(R.string.settings_appearance), onBack = onBack, modifier = modifier) {
        Text(
            text = stringResource(R.string.appearance_app_theme),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        ThemeTiles(chosen = theme, onChoose = onChoose, modifier = Modifier.padding(horizontal = 16.dp))
        if (theme != null) {
            Text(
                text = stringResource(theme.meaning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    // Said when the choice changes: the sentence is what tells what the tile does.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        NoteBox(
            text = stringResource(R.string.appearance_reading_note),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private val AppTheme.meaning: Int
    get() = when (this) {
        AppTheme.System -> R.string.appearance_system_meaning
        AppTheme.Light -> R.string.appearance_light_meaning
        AppTheme.Dark -> R.string.appearance_dark_meaning
    }
