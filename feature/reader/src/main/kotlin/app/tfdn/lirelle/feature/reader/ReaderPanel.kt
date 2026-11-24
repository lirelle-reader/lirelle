package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.reader.marks.CfiOrder
import app.tfdn.lirelle.core.reader.marks.Highlight
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.core.ui.rememberDayLabels
import app.tfdn.lirelle.core.ui.sheetListHeight
import java.time.Instant

internal const val ReaderPanelTag = "reader-panel"

private val TabHeight = 48.dp

/** On each side of a tab's name. */
private val TabPadding = 12.dp

/**
 * The panel of the top bar: the contents, the bookmarks and the notes, in three tabs, on the tab of
 * the state. A tap on a row goes there and closes the panel; the dates are told from [now].
 * [ReaderActions.dismissSheet] is called only when the user closes the panel. A source without annotations
 * has the contents alone, under their title (spec 2c).
 *
 * The sheet is a window of its own, over the reader's: the offer to undo a deletion is shown in it.
 */
@Composable
internal fun ReaderPanel(state: ReaderUiState, actions: ReaderActions, now: Instant) {
    val days = rememberDayLabels()
    GripSheet(onDismissRequest = actions::dismissSheet) {
        // The same height whatever the tab or the list's rows hold: the panel does not jump from one tab
        // to another, nor move with a list flung to its end.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetListHeight())
                .testTag(ReaderPanelTag),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val annotations = state.capabilities.annotations
                if (annotations) PanelTabs(state.panelTab, actions::showPanelTab) else ContentsTitle()
                when (if (annotations) state.panelTab else PanelTab.Contents) {
                    PanelTab.Contents -> TocList(
                        toc = state.toc,
                        currentId = state.location?.tocId,
                        onChoose = actions::goToToc,
                        modifier = Modifier.weight(1f),
                    )
                    PanelTab.Bookmarks -> BookmarkList(state, actions) { days.of(it, now) }
                    PanelTab.Notes -> NoteList(state, actions) { days.of(it, now) }
                }
            }
            state.undo?.let {
                val place = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                UndoBar(it, actions::undo, actions::dismissUndo, place)
            }
        }
    }
}

/** Whether a panel drawn in a window of its own is open: the one of contents, bookmarks and notes, or the search's. */
internal val ReaderUiState.panelWindowOpen: Boolean
    get() = openSheet == ReaderSheet.Contents || (openSheet == ReaderSheet.Search && search != null)

/**
 * The panel of contents, bookmarks and notes, or the search's panel, whichever the state opens. The
 * dates of the marks are told from [now], else from the moment the panel opened.
 */
@Composable
internal fun PanelWindows(state: ReaderUiState, actions: ReaderActions, now: Instant?) {
    when (state.openSheet) {
        ReaderSheet.Contents -> {
            val opened = remember { Instant.now() }
            ReaderPanel(state, actions, now = now ?: opened)
        }
        ReaderSheet.Search -> state.search?.let { SearchPanel(it, actions, state.undo) }
        else -> Unit
    }
}

/**
 * The three tabs share the width when their names fit in a third of it; otherwise each is as wide
 * as its name, at least a third, and the row scrolls sideways: no name is cut short, and the row
 * never ends before the edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelTabs(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    val colours = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Rounded down, the last tab taking what is left: the thirds fill the row to the pixel.
        val thirdPx = constraints.maxWidth / PanelTab.entries.size
        val third = with(LocalDensity.current) { thirdPx.toDp() }
        val last = with(LocalDensity.current) { (constraints.maxWidth - thirdPx * (PanelTab.entries.size - 1)).toDp() }
        PrimaryScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 0.dp, minTabWidth = third) {
            PanelTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = if (tab == PanelTab.entries.last()) Modifier.widthIn(min = last) else Modifier,
                    selectedContentColor = colours.primary,
                    unselectedContentColor = colours.onSurfaceVariant,
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = TabHeight)
                            .padding(horizontal = TabPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                            SingleLineLabel(stringResource(tab.label))
                        }
                    }
                }
            }
        }
    }
}

private val PanelTab.label: Int
    get() = when (this) {
        PanelTab.Contents -> R.string.reader_tab_contents
        PanelTab.Bookmarks -> R.string.reader_tab_bookmarks
        PanelTab.Notes -> R.string.reader_tab_notes
    }

/** The bookmarks in reading order, [day] telling the day of each; with none, how to add one. */
@Composable
private fun BookmarkList(state: ReaderUiState, actions: ReaderActions, day: (Instant) -> String) {
    val bookmarks = state.marks.bookmarks
    val rows = remember(bookmarks) { bookmarks.sortedWith(compareBy(CfiOrder) { it.cfi }) }
    if (rows.isEmpty()) {
        val empty = if (state.settings.cornerBookmark) {
            R.string.reader_bookmarks_empty_corner
        } else {
            R.string.reader_bookmarks_empty
        }
        EmptyTab(stringResource(empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.id.value }) { bookmark ->
            BookmarkRow(
                bookmark = bookmark,
                chapter = sectionLabel(bookmark.cfi, state.sectionLabels),
                day = day(bookmark.createdAt),
                onOpen = actions::goToMark,
                onDelete = { actions.deleteBookmark(bookmark.id) },
            )
        }
    }
}

/**
 * The highlights in reading order, those the server could not place last; one it is still placing
 * shows once it is placed; [day] tells the day of each. With none, how to make one.
 */
@Composable
private fun NoteList(state: ReaderUiState, actions: ReaderActions, day: (Instant) -> String) {
    val highlights = state.marks.highlights
    val rows = remember(highlights) {
        highlights.filterNot { it.cfi == null && it.positionStatus == PositionStatus.Pending }
            .sortedWith(compareBy(CfiOrder) { it.cfi })
    }
    if (rows.isEmpty()) {
        EmptyTab(stringResource(R.string.reader_notes_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.id.value }) { highlight ->
            NoteRow(
                highlight = highlight,
                chapter = highlight.chapter(state.sectionLabels),
                day = day(highlight.createdAt),
                onOpen = { open(highlight, actions) },
            )
        }
    }
}

/** To the highlight, or to the start of its chapter when it cannot be placed. */
private fun open(highlight: Highlight, actions: ReaderActions) {
    val cfi = highlight.cfi
    val chapterIndex = highlight.chapterIndex
    when {
        cfi != null -> actions.goToMark(cfi)
        chapterIndex != null -> actions.goToChapter(chapterIndex)
    }
}

/** Where the tabs are when the contents are alone: their name, a heading. */
@Composable
private fun ContentsTitle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TabHeight)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.reader_tab_contents),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun EmptyTab(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}


