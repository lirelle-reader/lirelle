package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.feature.reader.bridge.SearchHit

internal const val SearchPanelTag = "reader-search-panel"

/** As high as the panel of contents: a strip of the page stays above it, and a tap there closes it. */
private const val PanelHeight = 0.9f

private const val Hundred = 100

private val Gutter = 24.dp

/** As large as its touch target. */
private val CloseButtonSize = 48.dp

/**
 * The search's panel: the field, how far the search has got or what it found, and the results by
 * chapter. A tap on a result goes there; the close button ends the search, outlines and all. [ReaderActions.dismissSheet]
 * is called only when the user closes the panel, which leaves the results on the page.
 *
 * The sheet is a window of its own, over the reader's: the offer to undo a deletion ([undo]) is shown in it.
 */
@Composable
internal fun SearchPanel(search: SearchUi, actions: ReaderActions, undo: UndoUi? = null) {
    GripSheet(onDismissRequest = actions::dismissSheet) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(PanelHeight)
                .testTag(SearchPanelTag),
        ) {
            // The sheet keeps its content clear of the keyboard: the results end above it.
            Column(modifier = Modifier.fillMaxSize()) {
                SearchField(search, actions)
                SearchStatus(search)
                SearchResults(search, actions::showResult)
            }
            undo?.let {
                val place = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                UndoBar(it, actions::undo, actions::dismissUndo, place)
            }
        }
    }
}

/**
 * The close button and the field. The field's text is its own while it is typed, and handed to the
 * reader as it changes; it starts from the search's query and takes the focus when that is empty.
 * The keyboard's search action searches at once.
 */
@Composable
private fun SearchField(search: SearchUi, actions: ReaderActions) {
    val query = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(search.query) }
    var handed by remember { mutableStateOf(search.query) }
    val reader by rememberUpdatedState(actions)
    val hand = { text: String ->
        if (text != handed) {
            handed = text
            reader.editQuery(text)
        }
    }
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect(hand) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (search.query.isEmpty()) focus.requestFocus() }
    Row(
        modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::closeSearch, modifier = Modifier.size(CloseButtonSize)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reader_search_close))
        }
        OutlinedTextField(
            state = query,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            placeholder = { Text(stringResource(R.string.reader_search_book)) },
            inputTransformation = InputTransformation.maxLength(MaxQueryLength),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = {
                hand(query.text.toString())
                actions.submitQuery()
            },
            shape = RoundedCornerShape(28.dp),
        )
    }
}

/**
 * How far the search has got while it runs, then what it found; nothing before anything is searched.
 * The results counted are those held, as the bar on the page counts them.
 */
@Composable
private fun SearchStatus(search: SearchUi) {
    if (search.searchId == null) return
    val held = search.groups.sumOf { it.hits.size }
    val percent = (search.fraction * Hundred).toInt().coerceIn(0, Hundred)
    val text = when {
        search.running -> stringResource(R.string.reader_search_progress, percent)
        held == 0 -> stringResource(R.string.reader_search_none)
        search.truncated -> stringResource(R.string.reader_search_truncated)
        else -> {
            val chapters = search.groups.chapterCount()
            stringResource(
                R.string.reader_search_summary,
                pluralStringResource(R.plurals.reader_search_results, held, held),
                pluralStringResource(R.plurals.reader_search_chapters, chapters, chapters),
            )
        }
    }
    Column(modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
        Text(
            text = text,
            // Said once the search is over, not at every step of its progress.
            modifier = if (search.running) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (search.running) {
            LinearProgressIndicator(
                progress = { search.fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

/**
 * Each chapter under its heading, then its results; [onShow] is given the result's index across the
 * chapters. A chapter over several files, whose rows follow one another under the same label, has one heading.
 */
@Composable
private fun ColumnScope.SearchResults(search: SearchUi, onShow: (searchId: Int, index: Int) -> Unit) {
    val searchId = search.searchId ?: return
    val untitled = stringResource(R.string.reader_search_untitled)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        var start = 0
        search.groups.forEachIndexed { row, group ->
            val first = start
            if (row == 0 || group.heading != search.groups[row - 1].heading) {
                item(key = "chapter-$row") { ChapterHeading(group.heading ?: untitled) }
            }
            itemsIndexed(group.hits, key = { index, _ -> "result-$row-$index" }) { index, hit ->
                ResultRow(hit) { onShow(searchId, first + index) }
            }
            start += group.hits.size
        }
    }
}

@Composable
private fun ChapterHeading(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The match in its context, in bold; on as many lines as it needs. */
@Composable
private fun ResultRow(hit: SearchHit, onClick: () -> Unit) {
    val emphasis = MaterialTheme.colorScheme.primary
    val text = remember(hit, emphasis) {
        buildAnnotatedString {
            append(hit.pre)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = emphasis)) { append(hit.match) }
            append(hit.post)
        }
    }
    Column {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.reader_search_go_to_result), onClick = onClick)
                .padding(horizontal = Gutter, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Serif,
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = Gutter))
    }
}

/** The label a row's heading shows; null for a part of the book with none. */
private val SearchRow.heading: String? get() = label?.takeIf { it.isNotBlank() }

/** How many chapters: rows that follow one another under the same label are one chapter over several files. */
private fun List<SearchRow>.chapterCount(): Int = indices.count { it == 0 || this[it].heading != this[it - 1].heading }
