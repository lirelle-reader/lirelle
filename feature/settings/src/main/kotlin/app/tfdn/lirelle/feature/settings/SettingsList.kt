package app.tfdn.lirelle.feature.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.settings.appearance.AppTheme

/** The test tag of the row of a page. */
internal fun settingsRowTag(page: SettingsPage): String = "settings-row-${page.name}"

/**
 * The pages of the settings, one row each, then the note about the readers.
 *
 * @param selected the page shown beside the list, whose row is marked; null where the list is alone on the
 *   screen: a row then leads elsewhere, which its chevron tells.
 * @param appTheme the app's theme, told by the row of Appearance; null until it is known.
 */
@Composable
internal fun SettingsList(
    selected: SettingsPage?,
    appTheme: AppTheme?,
    onChoose: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inPane = selected != null
    val nameWidth = widestPageName()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The icons go where a name would otherwise be cut in the middle of a word: a narrow window, large text.
        val look = SettingsRowLook(
            inPane = inPane,
            decorated = decoratedRowWidth(nameWidth, inPane) <= maxWidth,
        )
        Column {
            SettingsPage.entries.forEach { page ->
                SettingsRow(
                    page = page,
                    about = page.about(appTheme)?.let { stringResource(it) },
                    marked = page == selected,
                    look = look,
                    onClick = { onChoose(page) },
                )
            }
            ReadersNote(boxed = !inPane)
        }
    }
}

/** The note under the rows: set apart in a box on a screen of its own, small at the foot of the list in a pane. */
@Composable
private fun ReadersNote(boxed: Boolean) {
    val note = stringResource(R.string.settings_readers_note)
    if (boxed) {
        NoteBox(note, Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
    } else {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

/** The room the longest name of a page takes on one line, at the current text size. */
@Composable
private fun widestPageName(): Dp {
    val names = SettingsPage.entries.map { stringResource(it.title) }
    val style = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(names, style, density) {
        val widest = names.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        // One pixel more: the width was rounded.
        with(density) { (widest + 1).toDp() }
    }
}

/** What the row of Appearance says of the theme, and the others of what their page holds. */
private fun SettingsPage.about(appTheme: AppTheme?): Int? = when (this) {
    SettingsPage.Appearance -> when (appTheme) {
        AppTheme.System -> R.string.settings_appearance_theme_system
        AppTheme.Light -> R.string.settings_appearance_theme_light
        AppTheme.Dark -> R.string.settings_appearance_theme_dark
        null -> null
    }
    SettingsPage.Reading -> R.string.settings_reading_about
    SettingsPage.Comics -> R.string.settings_comics_about
    SettingsPage.Player -> R.string.settings_player_about
    SettingsPage.Downloads -> R.string.settings_downloads_about
}
