package app.tfdn.lirelle.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.reader.settings.ReaderSettings

/**
 * The options used less often: the layout, the volume keys, the brightness gesture.
 *
 * @param cornerBookmark false for a source without annotations.
 */
@Composable
internal fun MoreOptions(
    settings: ReaderSettings,
    actions: ReaderActions,
    modifier: Modifier = Modifier,
    cornerBookmark: Boolean = true,
) {
    SheetFrame(
        title = stringResource(R.string.reader_settings_more),
        onDone = actions::dismissSheet,
        onBack = { actions.showSheet(ReaderSheet.Settings) },
        modifier = modifier,
    ) {
        LayoutSettings(settings, actions::updateSettings, cornerBookmark = cornerBookmark)
    }
}
