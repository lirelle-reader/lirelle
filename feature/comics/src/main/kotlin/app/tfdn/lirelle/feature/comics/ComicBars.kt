package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewComfy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.R as UiR

const val ComicTopBarTag = "comic-top-bar"
const val ComicBottomBarTag = "comic-bottom-bar"
const val ComicSettingsButtonTag = "comic-settings-button"
const val ComicPanelModeButtonTag = "comic-panel-mode-button"

/** How much of the page shows through a bar: next to nothing, enough to tell it lies over the page. */
private const val BarAlpha = 0.94f

/** The smallest side of anything that can be pressed. */
internal val MinTarget = 48.dp

/** The colour of the reader's bars: the app's ground, the page barely showing through. */
@Composable
internal fun barColour(): Color = MaterialTheme.colorScheme.surface.copy(alpha = BarAlpha)

/**
 * A bar of the reader, across the screen. It names the colour of what it holds: its translucent ground is no
 * colour of the scheme, so `Surface` would find none for it and leave the one of whatever is around the reader.
 */
@Composable
private fun ReaderBar(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = barColour(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

/**
 * Back, the album's title on one line and under it its series and number, then, for an album that can be read
 * panel by panel, the button that turns that on and off, and the reading settings.
 *
 * @param onSettings opens the settings sheet; null while there is no album to set, and then no button.
 * @param onPanelMode reading panel by panel is asked to be turned on (true) or off; null while there is no album
 *   to read so, and then no button.
 */
@Composable
internal fun ComicTopBar(
    state: ComicReaderUiState,
    onBack: () -> Unit,
    onSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onPanelMode: ((Boolean) -> Unit)? = null,
) {
    val edges = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ReaderBar(modifier.testTag(ComicTopBarTag)) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(edges)
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(MinTarget)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.navigate_back))
            }
            BarTitle(state.title, state.series, Modifier.weight(1f).padding(horizontal = 4.dp))
            if (onPanelMode != null && state.panels.offered) PanelModeButton(state.panels.on, onPanelMode)
            if (onSettings != null) {
                IconButton(onClick = onSettings, modifier = Modifier.size(MinTarget).testTag(ComicSettingsButtonTag)) {
                    Icon(Icons.Outlined.Tune, stringResource(R.string.comics_settings))
                }
            }
        }
    }
}

/**
 * Reading panel by panel, on or off: the icon alone when off, on a filled ground when on. What it is called says
 * which, for who does not see the ground.
 */
@Composable
private fun PanelModeButton(on: Boolean, onChange: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    IconToggleButton(
        checked = on,
        onCheckedChange = onChange,
        modifier = Modifier.size(MinTarget).testTag(ComicPanelModeButtonTag),
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = colours.primary,
            checkedContentColor = colours.onPrimary,
        ),
    ) {
        Icon(
            Icons.Outlined.ViewComfy,
            stringResource(if (on) R.string.comics_panels_on else R.string.comics_panels_off),
        )
    }
}

/** The title, and the series line as the book's page writes it; each on one line, cut short when too long. */
@Composable
private fun BarTitle(title: String, series: SeriesLine?, modifier: Modifier) {
    Column(modifier) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (series != null) {
            Text(
                text = series.index?.let { stringResource(UiR.string.book_series_volume, series.name, it) }
                    ?: series.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The slider over the pages, and under it the page or the two pages shown and how far they are in the album. */
@Composable
internal fun ComicBottomBar(state: ComicReaderUiState, navigation: ComicNavigation, modifier: Modifier = Modifier) {
    ReaderBar(modifier.testTag(ComicBottomBarTag)) {
        Column(
            Modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            PageSlider(state, navigation)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.label?.let { pagesText(it) }.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(UiR.string.progress_percent, state.percent.toInt()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** "Page 12 of 48", or "Pages 12-13 of 48" with two pages. */
@Composable
private fun pagesText(label: PageLabel): String = when (val last = label.last) {
    null -> stringResource(R.string.comics_bar_page, label.first, label.count)
    else -> stringResource(R.string.comics_bar_pages, label.first, last, label.count)
}
