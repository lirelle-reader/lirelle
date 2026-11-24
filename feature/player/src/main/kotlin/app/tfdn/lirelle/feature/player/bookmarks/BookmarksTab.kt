package app.tfdn.lirelle.feature.player.bookmarks

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.chapterNames
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import app.tfdn.lirelle.feature.player.controller.PlayerUiState

internal const val BookmarkListTag = "player-bookmark-list"
internal const val HighlightedBookmarkTag = "player-bookmark-highlighted"

private val TabMinHeight = 48.dp
private val TabIndicator = 3.dp
private val RowMinHeight = 52.dp
private val MarkerSize = 18.dp
private const val NoteLines = 2

/**
 * The chapters' place, in the sheet or the column always shown: the tabs "Chapters | Bookmarks · N" ("Parts" for a
 * book in parts), then
 * [chapters] or the bookmarks, whose list is read again each time their tab opens. A bookmark tapped is gone
 * to, then [onGone] (the sheet closes).
 */
@Composable
internal fun ChaptersAndBookmarks(
    state: PlayerUiState,
    bookmarks: PlayerBookmarks,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        ChaptersTabs(bookmarks.tab, state.chapterNames.title, state.bookmarks.size, bookmarks::select)
        when (bookmarks.tab) {
            ChaptersTab.Chapters -> chapters()
            ChaptersTab.Bookmarks -> {
                LaunchedEffect(Unit) { bookmarks.refresh() }
                BookmarksTab(
                    bookmarks = state.bookmarks,
                    chapters = state.chapters,
                    highlight = bookmarks.highlighted?.let { Highlight(it, bookmarks.highlights) },
                    actions = BookmarkRowActions(
                        onGo = {
                            bookmarks.goTo(it)
                            onGone()
                        },
                        onEdit = bookmarks::edit,
                        onDelete = bookmarks::delete,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The two tabs, on one line each ([tabWidths]): half the width each when both labels fit in half, else the
 * longer label gets its width and the other the rest; only when the two do not fit side by side does the row
 * scroll sideways. No label is cut short.
 */
@Composable
private fun ChaptersTabs(tab: ChaptersTab, @StringRes chapters: Int, count: Int, onSelect: (ChaptersTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val full = constraints.maxWidth
        Layout(
            content = {
                TabItem(stringResource(chapters), tab == ChaptersTab.Chapters) {
                    onSelect(ChaptersTab.Chapters)
                }
                TabItem(stringResource(R.string.player_bookmarks_tab, count), tab == ChaptersTab.Bookmarks) {
                    onSelect(ChaptersTab.Bookmarks)
                }
            },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
        ) { measurables, _ ->
            val widths = tabWidths(full, measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) })
            val height = measurables.zip(widths).maxOf { (tab, width) -> tab.minIntrinsicHeight(width) }
            val placeables = measurables.zip(widths).map { (tab, width) ->
                tab.measure(Constraints.fixed(width, height))
            }
            layout(widths.sum(), height) {
                var x = 0
                placeables.forEach {
                    it.place(x, 0)
                    x += it.width
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The widths of two tabs across [full] pixels, from the widths their labels [need]: halves when both fit in
 * half; else the wider gets what it needs and the other the rest; wider together than [full], each its own (the
 * row then scrolls).
 */
internal fun tabWidths(full: Int, need: List<Int>): List<Int> {
    val (first, second) = need
    val half = full / 2
    return when {
        first <= half && second <= half -> listOf(half, full - half)
        first + second > full -> listOf(first, second)
        first > half -> listOf(first, full - first)
        else -> listOf(full - second, second)
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val accent = colours.primary
    Box(
        modifier = Modifier
            .heightIn(min = TabMinHeight)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val line = TabIndicator.toPx()
                    drawRect(accent, Offset(0f, size.height - line), Size(size.width, line))
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val style = MaterialTheme.typography.titleSmall
        ProvideTextStyle(if (selected) style.copy(fontWeight = FontWeight.SemiBold) else style) {
            SingleLineLabel(label, color = if (selected) accent else colours.onSurfaceVariant)
        }
    }
}

/** The bookmark highlighted, the [count]th time one was. */
private data class Highlight(val id: String, val count: Int)

/** What a bookmark's row does: a tap goes there; its menu, or a screen reader's actions, edit or delete it. */
private class BookmarkRowActions(
    val onGo: (AudioBookmark) -> Unit,
    val onEdit: (AudioBookmark) -> Unit,
    val onDelete: (AudioBookmark) -> Unit,
)

/**
 * The bookmarks in position order; the [highlight]ed one is brought into view, each time it is asked, and
 * marked. Empty, it says how to add one.
 */
@Composable
private fun BookmarksTab(
    bookmarks: List<AudioBookmark>,
    chapters: List<ChapterRow>,
    highlight: Highlight?,
    actions: BookmarkRowActions,
    modifier: Modifier,
) {
    val highlighted = highlight?.id
    if (bookmarks.isEmpty()) {
        EmptyBookmarks(modifier)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(highlight) {
        val index = bookmarks.indexOfFirst { it.id == highlighted }
        if (index >= 0) listState.scrollToItem(index)
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(BookmarkListTag),
        state = listState,
    ) {
        items(bookmarks, key = { it.id }) { bookmark ->
            val place = remember(bookmark.positionMs, chapters) {
                BookmarkPlace.of(bookmark.positionMs, chapters).text
            }
            BookmarkLine(bookmark, place, bookmark.id == highlighted, actions)
        }
    }
}

/**
 * A bookmark: its title on one line, where it is, and two lines of its note. A long press opens a menu to
 * edit or delete it; a screen reader has the same two as actions of the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkLine(bookmark: AudioBookmark, place: String, highlighted: Boolean, actions: BookmarkRowActions) {
    var menu by remember { mutableStateOf(false) }
    val edit = stringResource(R.string.player_bookmark_edit)
    val delete = stringResource(R.string.player_bookmark_delete)
    val colours = MaterialTheme.colorScheme
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (highlighted) Modifier.testTag(HighlightedBookmarkTag) else Modifier)
                .background(if (highlighted) colours.surfaceContainerHigh else Color.Transparent)
                // No button role: the note takes two lines, which a button's label may not.
                .combinedClickable(
                    onClickLabel = stringResource(R.string.player_bookmark_go),
                    onLongClickLabel = stringResource(R.string.player_bookmark_options),
                    onLongClick = { menu = true },
                    onClick = { actions.onGo(bookmark) },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(edit) { true.also { actions.onEdit(bookmark) } },
                        CustomAccessibilityAction(delete) { true.also { actions.onDelete(bookmark) } },
                    )
                }
                .heightIn(min = RowMinHeight)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Bookmark,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(MarkerSize),
                tint = colours.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(place, style = MaterialTheme.typography.bodyMedium, color = colours.onSurfaceVariant)
                bookmark.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colours.onSurfaceVariant,
                        maxLines = NoteLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { SingleLineLabel(edit) },
                onClick = {
                    menu = false
                    actions.onEdit(bookmark)
                },
            )
            DropdownMenuItem(
                text = { SingleLineLabel(delete) },
                onClick = {
                    menu = false
                    actions.onDelete(bookmark)
                },
            )
        }
    }
}

@Composable
private fun EmptyBookmarks(modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = stringResource(R.string.player_bookmarks_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
