package app.tfdn.lirelle.feature.player.bookmarks

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.feature.player.R
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which list the chapters' place shows: the chapters, or the bookmarks. */
internal enum class ChaptersTab { Chapters, Bookmarks }

/** The snackbars' words, in the screen's language. */
internal data class BookmarkTexts(
    val added: String,
    val edit: String,
    val exists: String,
    val deleted: String,
    val undo: String,
    val failed: String,
)

/**
 * The player screen's side of the bookmarks: the tab shown beside the chapters, the bookmark highlighted
 * there, the one being edited, and the [snackbars] that tell what became of an add or a deletion.
 *
 * @param openSheet opens the chapters sheet, on a window where the chapters are not always shown.
 */
@Stable
internal class PlayerBookmarks(
    private val actions: BookmarkActions,
    val snackbars: SnackbarHostState,
    private val scope: CoroutineScope,
    private val texts: BookmarkTexts,
    states: BookmarkStates,
    private val openSheet: () -> Unit,
) {
    var tab by states.tab
        private set

    /** The bookmark an add found already there: shown, and brought into view, in the tab. */
    var highlighted by states.highlighted
        private set

    /** How many times a bookmark was highlighted: the same one again is brought into view again. */
    var highlights by states.highlights
        private set

    /** The id of the bookmark whose dialog is open. */
    var editing by states.editing

    /**
     * A bookmark at the place playing; "Edit" in its snackbar opens it. One already at that second is shown
     * in the bookmarks tab, which [inSheet] opens in the chapters sheet. One that could not be added is told.
     */
    fun add(inSheet: Boolean) {
        scope.launch {
            when (val outcome = actions.addBookmark()) {
                is AddOutcome.Added -> {
                    val answer = snackbars.showSnackbar(texts.added, texts.edit, duration = SnackbarDuration.Long)
                    if (answer == SnackbarResult.ActionPerformed) editing = outcome.bookmark.id
                }
                is AddOutcome.Existing -> {
                    tab = ChaptersTab.Bookmarks
                    highlighted = outcome.bookmark.id
                    highlights++
                    if (inSheet) openSheet()
                    snackbars.showSnackbar(texts.exists)
                }
                is AddOutcome.Taken -> snackbars.showSnackbar(texts.exists)
                null -> snackbars.showSnackbar(texts.failed)
            }
        }
    }

    /**
     * [bookmark] leaves the list at once, with "Undo"; it is deleted once the snackbar goes without it, or
     * when the screen goes first.
     */
    fun delete(bookmark: AudioBookmark) {
        actions.deleteBookmark(bookmark)
        scope.launch {
            val answer = try {
                snackbars.showSnackbar(texts.deleted, texts.undo, duration = SnackbarDuration.Long)
            } catch (gone: CancellationException) {
                actions.confirmDelete(bookmark)
                throw gone
            }
            when (answer) {
                SnackbarResult.ActionPerformed -> actions.undoDelete(bookmark)
                SnackbarResult.Dismissed -> actions.confirmDelete(bookmark)
            }
        }
    }

    fun edit(bookmark: AudioBookmark) {
        editing = bookmark.id
    }

    fun goTo(bookmark: AudioBookmark) = actions.goToBookmark(bookmark)

    /** The bookmarks tab opened: their list is read again. */
    fun refresh() = actions.refreshBookmarks()

    fun select(chosen: ChaptersTab) {
        tab = chosen
        highlighted = null
    }

    /**
     * The chapters sheet closed: nothing stays highlighted, and it opens on the chapters next time, as the
     * "Chapters" tile says. The column always shown keeps its tab.
     */
    fun sheetClosed() {
        tab = ChaptersTab.Chapters
        highlighted = null
    }
}

/** What [PlayerBookmarks] keeps across a rotation. */
internal class BookmarkStates(
    val tab: MutableState<ChaptersTab>,
    val highlighted: MutableState<String?>,
    val highlights: MutableState<Int>,
    val editing: MutableState<String?>,
)

/** The screen's bookmarks, kept across a rotation; [openSheet] opens the chapters sheet. */
@Composable
internal fun rememberPlayerBookmarks(actions: BookmarkActions, openSheet: () -> Unit): PlayerBookmarks {
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val states = BookmarkStates(
        tab = rememberSaveable { mutableStateOf(ChaptersTab.Chapters) },
        highlighted = rememberSaveable { mutableStateOf<String?>(null) },
        highlights = rememberSaveable { mutableStateOf(0) },
        editing = rememberSaveable { mutableStateOf<String?>(null) },
    )
    val texts = BookmarkTexts(
        added = stringResource(R.string.player_bookmark_added),
        edit = stringResource(R.string.player_bookmark_edit),
        exists = stringResource(R.string.player_bookmark_exists),
        deleted = stringResource(R.string.player_bookmark_deleted),
        undo = stringResource(R.string.player_bookmark_undo),
        failed = stringResource(R.string.player_bookmark_failed),
    )
    val open by rememberUpdatedState(openSheet)
    return remember(actions, texts) { PlayerBookmarks(actions, snackbars, scope, texts, states) { open() } }
}

/** The room a snackbar takes around its message and its action: its margins, its paddings and the action's. */
private val SnackbarChrome = 96.dp

/**
 * A bookmark's snackbar: its action beside the message when both fit on one line, under it otherwise, so that
 * the message keeps its words whole.
 */
@Composable
internal fun BookmarkSnackbar(data: SnackbarData) {
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val message = measurer.measure(data.visuals.message, MaterialTheme.typography.bodyMedium).size.width
        val action = data.visuals.actionLabel
            ?.let { measurer.measure(it, MaterialTheme.typography.labelLarge).size.width } ?: 0
        val room = constraints.maxWidth - with(LocalDensity.current) { SnackbarChrome.roundToPx() }
        Snackbar(data, actionOnNewLine = message + action > room)
    }
}
