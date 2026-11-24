package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

internal const val SearchBarTag = "reader-search-bar"

private val SearchBarMaxWidth = 560.dp

/** A count of four digits, as wide as any count of results, which stop at 1 000. */
private const val WidestCount = 9_999

/** Above and below the search's bar, in its room under the page. */
private val SearchBarGap = 6.dp

/** As large as its touch target. */
private val BarButtonSize = 48.dp

/**
 * The search's bar on the page: "3 / 27" between the previous and next arrows, then "Go back", the
 * way back to the list and the close button. Before a result is shown it counts them only, and the
 * arrows start from the first or the last. Its buttons go under the counter when the line is too
 * narrow; a tap on it never reaches the page, whose controls stay as they are.
 *
 * @param onGoBack "Go back" after a jump, null when there is none to undo. The bar keeps its room
 *   either way: it never changes height, which would lay the book's page out again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchBar(
    search: SearchUi,
    actions: SearchActions,
    onGoBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val count = search.groups.sumOf { it.hits.size }
    Surface(
        modifier = modifier
            .testTag(SearchBarTag)
            .padding(horizontal = 12.dp)
            .widthIn(max = SearchBarMaxWidth)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarButton(Icons.Filled.KeyboardArrowUp, R.string.reader_search_previous, count > 0) {
                    actions.previousResult()
                }
                Counter(
                    current = search.current,
                    count = count,
                    more = search.truncated,
                    announce = search.current != null && !search.running,
                )
                BarButton(Icons.Filled.KeyboardArrowDown, R.string.reader_search_next, count > 0) {
                    actions.nextResult()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Without a jump to undo, its room is kept, empty.
                BarButton(
                    Icons.AutoMirrored.Filled.Undo,
                    R.string.reader_go_back,
                    enabled = onGoBack != null,
                    modifier = if (onGoBack == null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
                ) { onGoBack?.invoke() }
                BarButton(Icons.AutoMirrored.Filled.List, R.string.reader_search_back_to_results) {
                    actions.backToResults()
                }
                BarButton(Icons.Filled.Close, R.string.reader_search_close) { actions.closeSearch() }
            }
        }
    }
}

/**
 * "3 / 27", said "Result 3 of 27"; "– / 27", said "27 results", before a result is shown; with a "+"
 * when there are [more] than the search kept ("3 / 1000+", said "Result 3 of more than 1,000"). Said
 * again as it changes while [announce]: once the user moves to another result. As wide as the widest
 * count, so that the next arrow does not move under the finger.
 */
@Composable
private fun Counter(current: Int?, count: Int, more: Boolean, announce: Boolean) {
    val text = counterText(current, count, more)
    val spoken = counterSpoken(current, count, more)
    val style = MaterialTheme.typography.titleMedium
    val widest = listOf(
        stringResource(R.string.reader_search_counter, WidestCount, WidestCount),
        stringResource(R.string.reader_search_counter_more, WidestCount, WidestCount),
    )
    val measurer = rememberTextMeasurer()
    val width = with(LocalDensity.current) {
        widest.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }.toDp()
    }
    Text(
        text = text,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .widthIn(min = width)
            .semantics {
                contentDescription = spoken
                if (announce) liveRegion = LiveRegionMode.Polite
            },
        style = style,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun counterText(current: Int?, count: Int, more: Boolean): String = when {
    current == null && more -> stringResource(R.string.reader_search_counter_none_more, count)
    current == null -> stringResource(R.string.reader_search_counter_none, count)
    more -> stringResource(R.string.reader_search_counter_more, current + 1, count)
    else -> stringResource(R.string.reader_search_counter, current + 1, count)
}

@Composable
private fun counterSpoken(current: Int?, count: Int, more: Boolean): String {
    val limit = NumberFormat.getInstance(LocalConfiguration.current.locales[0]).format(count)
    return when {
        current == null && more -> stringResource(R.string.reader_search_results_more, limit)
        current == null -> pluralStringResource(R.plurals.reader_search_results, count, count)
        more -> stringResource(R.string.reader_search_counter_spoken_more, current + 1, limit)
        else -> stringResource(R.string.reader_search_counter_spoken, current + 1, count)
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    label: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(BarButtonSize)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The search's bar is on the page while nothing covers the place it takes: a footnote, a selection, its panel. */
internal val ReaderUiState.searchBarShown: Boolean
    get() = search?.onPage == true && !noteOpen && selection == null && openSheet != ReaderSheet.Search

/**
 * Where the search's bar is drawn: in a room of its own under the page, lifted by [lift] pixels.
 *
 * @property onRoom the room's height, the bar and the space around it, for what stacks above it.
 */
internal class SearchBarPlace(val lift: () -> Int, val onRoom: (Int) -> Unit)

/** The search's bar is in its room under the page, which is then shorter. */
internal val ReaderUiState.searchBarOnPage: Boolean
    get() = phase is ReaderPhase.Reading && opened && searchBarShown

/** The search's bar in its room under the page, while it is shown; "Go back" is in it after a jump. */
@Composable
internal fun SearchBarRoom(state: ReaderUiState, actions: ReaderActions, place: SearchBarPlace, modifier: Modifier) {
    val search = state.search?.takeIf { state.searchBarOnPage } ?: return
    SearchBar(
        search = search,
        actions = actions,
        onGoBack = if (state.goBackShown) actions::goBack else null,
        modifier = modifier
            // Moved where it is drawn, not where it is laid out: the page keeps its size.
            .offset { IntOffset(0, -place.lift()) }
            .onSizeChanged { place.onRoom(it.height) }
            .padding(vertical = SearchBarGap),
    )
}
