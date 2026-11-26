package app.tfdn.lirelle.feature.search

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.FailedState
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadingTag
import app.tfdn.lirelle.core.ui.OnShown

internal const val SearchFieldTag = "search-field"

/** The zone of the genres, or of the books found: the whole column on a narrow window. */
internal const val SearchMainTag = "search-main"

/** The zone of the recent searches, or of the series and authors found. */
internal const val SearchSideTag = "search-side"

/** The share of the width of each zone of a wide window: two thirds, one third. */
internal const val MainWeight = 2f
internal const val SideWeight = 1f

private val FieldMaxWidth = 700.dp
private val FieldBottomPadding = 8.dp

/** What the search screen can open. */
class SearchActions(
    val onOpenBook: (BookId) -> Unit,
    val onOpenSeries: (SeriesId) -> Unit,
    val onOpenAuthor: (AuthorId) -> Unit,
    val onOpenGenre: (String) -> Unit,
    val onSeeAllBooks: (String) -> Unit,
    val onSeeAllSeries: (String) -> Unit,
    val onSeeAllAuthors: (String) -> Unit,
)

/**
 * These actions, each telling [onResultOpened] first that the text led somewhere. Opening a
 * genre does not: it has nothing to do with the text typed.
 */
private fun SearchActions.recording(onResultOpened: () -> Unit): SearchActions {
    fun <T> ((T) -> Unit).recorded(): (T) -> Unit = { value ->
        onResultOpened()
        this(value)
    }
    return SearchActions(
        onOpenBook = onOpenBook.recorded(),
        onOpenSeries = onOpenSeries.recorded(),
        onOpenAuthor = onOpenAuthor.recorded(),
        onOpenGenre = onOpenGenre,
        onSeeAllBooks = onSeeAllBooks.recorded(),
        onSeeAllSeries = onSeeAllSeries.recorded(),
        onSeeAllAuthors = onSeeAllAuthors.recorded(),
    )
}

/** The Search destination. */
@Composable
fun SearchRoute(actions: SearchActions, modifier: Modifier = Modifier, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnShown(viewModel::onShown)
    SearchContent(
        state = state,
        actions = actions,
        onText = viewModel::onText,
        onRetry = viewModel::onRetry,
        onResultOpened = viewModel::onResultOpened,
        onRemoveRecent = viewModel::onRemoveRecent,
        onClearRecent = viewModel::onClearRecent,
        modifier = modifier,
    )
}

/**
 * The field, then what goes with its text: recent searches and genres while it is empty, the
 * results once something was searched.
 *
 * @param onResultOpened called before any of [actions] that follows a result or a "See all".
 */
@Composable
fun SearchContent(
    state: SearchUiState,
    actions: SearchActions,
    onText: (String) -> Unit,
    onRetry: () -> Unit,
    onResultOpened: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = remember(actions, onResultOpened) { actions.recording(onResultOpened) }
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    Column(
        modifier = modifier
            .fillMaxSize()
            // Under the status bar and above the keyboard: what is under the field stays reachable.
            .safeDrawingPadding()
            .padding(top = CollectionEdgePadding),
    ) {
        SearchField(
            text = state.text,
            onText = onText,
            modifier = Modifier
                .padding(start = CollectionEdgePadding, end = CollectionEdgePadding, bottom = FieldBottomPadding)
                .widthIn(max = FieldMaxWidth)
                .fillMaxWidth(),
        )
        when (val results = state.results) {
            null -> SearchStart(
                recent = state.recent,
                genres = state.genres,
                wide = wide,
                onRun = onText,
                onRemove = onRemoveRecent,
                onClear = onClearRecent,
                onOpenGenre = actions.onOpenGenre,
            )
            Loadable.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(LoadingTag),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            // Under the field, and scrolling: with the keyboard open there is little room left.
            is Loadable.Failed -> FailedState(results.error, onRetry, Modifier.verticalScroll(rememberScrollState()))
            is Loadable.Ready -> if (results.value.isEmpty) {
                EmptyMessage(
                    text = stringResource(R.string.search_nothing, results.value.text),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            } else {
                SearchResultsContent(results.value, wide, recording, state.showSeriesCounts, state.seeAllBooks)
            }
        }
    }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { SearchFieldText(text) }
    OutlinedTextField(
        value = field.showing(text),
        onValueChange = { field.onEdit(it, onText) },
        modifier = modifier.testTag(SearchFieldTag),
        // The hint is not cut: at a large text size it takes the lines it needs, and the field
        // is as tall as the hint until something is typed.
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onText("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The search already ran while typing: the key only puts the keyboard away.
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        singleLine = true,
        shape = CircleShape,
    )
}

/**
 * A column that scrolls on its own, with the margins of the screen.
 *
 * @param tag the test tag of the zone, null for a column that is not one of the two zones.
 * @param scroll remembered across rotation and the way back from a result, as the default is.
 */
@Composable
internal fun ScrollingZone(
    tag: String?,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .verticalScroll(scroll)
            .padding(horizontal = CollectionEdgePadding)
            .padding(bottom = CollectionEdgePadding),
        content = content,
    )
}
