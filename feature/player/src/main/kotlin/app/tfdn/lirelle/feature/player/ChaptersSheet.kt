package app.tfdn.lirelle.feature.player

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.core.ui.sheetListHeight
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkSnackbar
import app.tfdn.lirelle.feature.player.bookmarks.ChaptersAndBookmarks
import app.tfdn.lirelle.feature.player.bookmarks.PlayerBookmarks
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.controller.timeLeft

internal const val ChaptersSheetTag = "player-chapters-sheet"
internal const val ChapterListTag = "player-chapter-list"

/** How many chapters stay visible above the current one when the list opens. */
private const val ChaptersAbove = 2

private val RowMinHeight = 52.dp
private val MarkerSize = 22.dp
private val MarkerIconSize = 18.dp

/**
 * The chapters in a sheet; a tap on one goes to its start (with "Go back") and closes the sheet. The
 * sheet keeps one height whatever rows the list shows; the list scrolls on its own, and the sheet is
 * closed by its grip, a tap above it or back. [names] says what the rows are: chapters, parts, or a narration's
 * sections.
 * An audiobook's [bookmarks] are in a tab beside its chapters, with their snackbars at the bottom of the sheet.
 */
@Composable
internal fun ChaptersSheet(
    state: PlayerUiState,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
    names: ChapterNames = state.chapterNames,
    bookmarks: PlayerBookmarks? = null,
) {
    GripSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val chapters: @Composable ColumnScope.() -> Unit = {
            ChaptersHeader(state.chapters.size, state.totalMs, names = names)
            ChapterList(
                bookId = state.bookId,
                chapters = rememberedChapters(state),
                chapterIndex = state.chapterIndex,
                currentLeftMs = currentLeftMs(state),
                onChoose = {
                    onChoose(it)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .height(sheetListHeight())
                .testTag(ChaptersSheetTag),
        ) {
            if (bookmarks == null) {
                Column(content = chapters)
            } else {
                ChaptersAndBookmarks(state, bookmarks, onGone = onDismiss, chapters = chapters)
                SnackbarHost(bookmarks.snackbars, Modifier.align(Alignment.BottomCenter)) { BookmarkSnackbar(it) }
            }
        }
    }
}

/** What a list of chapters is called ([title]), and how its rows are counted ([count], a plural). */
internal data class ChapterNames(@StringRes val title: Int, @PluralsRes val count: Int)

/** An audiobook's chapters. */
internal val AudiobookChapters = ChapterNames(R.string.player_chapters, R.plurals.player_chapters_count)

/** The parts of an audiobook whose chapters have no titles of their own. */
internal val AudiobookParts = ChapterNames(R.string.player_parts, R.plurals.player_parts_count)

/** What the audiobook's list is called: its parts when it is in parts, its chapters otherwise. */
internal val PlayerUiState.chapterNames: ChapterNames get() = if (inParts) AudiobookParts else AudiobookChapters

/** "Chapters", with how many there are and the book's length when it is known; [names] for other rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChaptersHeader(
    count: Int,
    totalMs: Long?,
    modifier: Modifier = Modifier,
    names: ChapterNames = AudiobookChapters,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(names.title),
            modifier = Modifier
                .padding(end = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        val chapters = pluralStringResource(names.count, count, count)
        Text(
            text = totalMs?.let { stringResource(R.string.player_joined, chapters, durationText(it)) } ?: chapters,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Every chapter with its length: the ones heard greyed with a check, the current one in the accent
 * colour with its time left. Titles take the lines they need.
 *
 * The list shows the current chapter, a couple of rows below its top, when it opens, when its rows
 * arrive after it (a column always shown, composed before the book was read), and for another book
 * ([bookId]). It follows the chapters as they change only while the current one is in view: a list
 * the user scrolled elsewhere stays where they put it, until the playing chapter comes back into view.
 */
@Composable
internal fun ChapterList(
    bookId: BookId?,
    chapters: List<ChapterRow>,
    chapterIndex: Int,
    currentLeftMs: Long?,
    onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = topRowFor(chapterIndex))
    val hasRows = chapters.isNotEmpty()
    LaunchedEffect(bookId, hasRows) { if (hasRows) listState.scrollToItem(topRowFor(chapterIndex)) }
    var shown by remember { mutableIntStateOf(chapterIndex) }
    LaunchedEffect(chapterIndex) {
        val before = shown
        shown = chapterIndex
        if (before == chapterIndex || listState.isScrollInProgress) return@LaunchedEffect
        val wasInView = listState.layoutInfo.visibleItemsInfo.any { it.index == before }
        if (wasInView) listState.animateScrollToItem(topRowFor(chapterIndex))
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ChapterListTag),
        state = listState,
    ) {
        itemsIndexed(chapters) { index, chapter ->
            ChapterLine(chapter, currentLeftMs?.takeIf { chapter.current }, onClick = { onChoose(index) })
        }
    }
}

/** The row at the top of the list when [chapterIndex] is shown: a couple of rows above it stay in view. */
private fun topRowFor(chapterIndex: Int) = (chapterIndex - ChaptersAbove).coerceAtLeast(0)

/** How a chapter's row looks and what a screen reader adds to it, from where it is in the listening. */
private data class ChapterLook(val colour: Color, val state: String?, val marker: ImageVector?)

@Composable
private fun lookOf(chapter: ChapterRow): ChapterLook {
    val colours = MaterialTheme.colorScheme
    return when {
        chapter.current -> ChapterLook(
            colour = colours.primary,
            state = stringResource(R.string.player_chapter_playing),
            marker = Icons.AutoMirrored.Rounded.VolumeUp,
        )
        chapter.heard -> ChapterLook(
            colour = colours.onSurfaceVariant,
            state = stringResource(R.string.player_chapter_heard),
            marker = Icons.Rounded.Check,
        )
        else -> ChapterLook(colours.onSurface, null, null)
    }
}

@Composable
private fun ChapterLine(chapter: ChapterRow, currentLeftMs: Long?, onClick: () -> Unit) {
    val look = lookOf(chapter)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (chapter.current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            // No button role: a title takes the lines it needs, which a button's label may not.
            .clickable(onClick = onClick)
            .semantics { look.state?.let { stateDescription = it } }
            .heightIn(min = RowMinHeight)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(MarkerSize), contentAlignment = Alignment.Center) {
            look.marker?.let { Icon(it, null, Modifier.size(MarkerIconSize), tint = look.colour) }
        }
        Text(
            text = chapter.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = look.colour,
            fontWeight = if (chapter.current) FontWeight.SemiBold else FontWeight.Normal,
        )
        // The current chapter's time left, the others' length when it is known.
        val time = currentLeftMs?.let { stringResource(R.string.player_remaining, clockTime(it)) }
            ?: chapter.durationMs?.let(::clockTime)
        time?.let {
            val digits = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
            Text(it, style = digits, color = look.colour)
        }
    }
}

/** The chapters of [state], the same list while they do not change: the rows are not drawn again at each tick. */
@Composable
internal fun rememberedChapters(state: PlayerUiState) = remember(state.chapters) { state.chapters }

/** The time left in the current chapter, at the speed; null when its length is not known. */
internal fun currentLeftMs(state: PlayerUiState): Long? =
    state.chapterDurationMs?.let { timeLeft(it, state.chapterElapsedMs, state.speed) }
