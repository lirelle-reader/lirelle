package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationUi
import app.tfdn.lirelle.feature.reader.narration.WaveButton
import app.tfdn.lirelle.feature.reader.narration.loaded
import app.tfdn.lirelle.core.ui.R as UiR

internal const val ReaderTopBarTag = "reader-top-bar"
internal const val ReaderBottomBarTag = "reader-bottom-bar"
internal const val ReaderSliderTag = "reader-slider"

private const val Hundred = 100
private const val Ellipsis = "…"

/** The height of a bar's row of buttons: that of their touch targets. */
private val BarRowHeight = 48.dp

/**
 * Back, the book's title on one line, then the narration's wave when the file has one, search, the
 * bookmark of the page (filled when the page holds one), the panel of contents, bookmarks and notes,
 * and the reading settings. On a narrow window the title gives way: the buttons keep their size.
 *
 * @param onOpenNarration the wave while the book is narrated: the narration's panel.
 * @param bookmarks false for a source without annotations: no bookmark button.
 */
@Composable
internal fun ReaderTopBar(
    title: String,
    bookmarked: Boolean,
    actions: ReaderActions,
    modifier: Modifier = Modifier,
    narration: ReaderNarrationUi = ReaderNarrationUi(),
    onOpenNarration: () -> Unit = {},
    bookmarks: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BarRowHeight)
            .padding(horizontal = 4.dp)
            .testTag(ReaderTopBarTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.navigate_back), actions::leave)
        BarTitle(title, Modifier.weight(1f))
        val narrated = narration.active?.loaded == true
        if (narration.available || narrated) {
            WaveButton(
                active = narrated,
                accent = MaterialTheme.colorScheme.primary,
                onStart = actions::startHere,
                onOpenPanel = onOpenNarration,
            )
        }
        BarButton(Icons.Outlined.Search, stringResource(R.string.reader_search_book), actions::openSearch)
        if (bookmarks) {
            BarButton(
                icon = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                label = stringResource(
                    if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add,
                ),
                onClick = actions::toggleBookmark,
            )
        }
        BarButton(Icons.AutoMirrored.Outlined.Toc, stringResource(R.string.reader_panel)) {
            actions.showSheet(ReaderSheet.Contents)
        }
        BarButton(Icons.Outlined.Tune, stringResource(R.string.reader_settings)) {
            actions.showSheet(ReaderSheet.Settings)
        }
    }
}

/**
 * The book's title on one line, cut short with an ellipsis; left out when not even its first letter and the
 * ellipsis fit (a narrow window at a large text size, beside every button). A screen reader hears it all the same.
 */
@Composable
private fun BarTitle(title: String, modifier: Modifier) {
    val style = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = title
            },
    ) {
        val least = measurer.measure(title.take(1) + Ellipsis, style, maxLines = 1, softWrap = false).size.width
        if (constraints.maxWidth >= least) {
            Text(text = title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A button of a bar, as large as its touch target whatever room is left. */
@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(BarRowHeight)) {
        Icon(icon, contentDescription = label)
    }
}

/**
 * The current chapter and the percentage, a slider over the whole book, and the two chapter
 * buttons, which go one under the other when they do not fit side by side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderBottomBar(location: Location?, navigation: ReaderNavigation, modifier: Modifier = Modifier) {
    val fraction = location?.fraction?.toFloat() ?: 0f
    // Where the finger holds the slider. On release the thumb is the book's again: the page says
    // where it went, and says nothing when that is where it already was.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: fraction
    val percent = stringResource(UiR.string.progress_percent, (shown * Hundred).toInt().coerceIn(0, Hundred))
    val sliderLabel = stringResource(R.string.reader_position_slider)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ReaderBottomBarTag),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = location?.chapter.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = percent, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
        }
        SlimSlider(
            value = shown,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { navigation.seekTo(it.toDouble()) }
                dragged = null
            },
            enabled = location != null,
            modifier = Modifier
                .testTag(ReaderSliderTag)
                .semantics {
                    contentDescription = sliderLabel
                    stateDescription = percent
                },
        )
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = navigation::previousChapter, modifier = Modifier.heightIn(min = BarRowHeight)) {
                SingleLineLabel(stringResource(R.string.reader_previous_chapter))
            }
            TextButton(onClick = navigation::nextChapter, modifier = Modifier.heightIn(min = BarRowHeight)) {
                SingleLineLabel(stringResource(R.string.reader_next_chapter))
            }
        }
    }
}
