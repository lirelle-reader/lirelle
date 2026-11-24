package app.tfdn.lirelle.feature.player.bookmarks

import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.audio.bookmarks.AudioBookmarks
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The bookmarks of the audiobook in the player: its list ([shown]), without the ones being deleted, read again
 * from the server each time a book is loaded, and what the player does with them. A narration has none.
 *
 * Changes go through [scope], which outlives the screen: one asked for as the screen goes is still made.
 *
 * @param jumpTo goes to a place on the book's timeline, with "Go back".
 */
internal class BookmarksPart(
    private val bookmarks: AudioBookmarks,
    private val scope: CoroutineScope,
    private val jumpTo: (bookTimeMs: Long) -> Unit,
) : BookmarkActions {
    private var current = PlayerUiState()
    private val book = MutableStateFlow<BookId?>(null)

    /** The bookmarks deleted whose "Undo" snackbar shows: hidden from the list, not deleted yet. */
    private val deleting = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val shown: Flow<List<AudioBookmark>> = book
        .flatMapLatest { id -> id?.let(bookmarks::of) ?: flowOf(emptyList()) }
        .combine(deleting) { list, hidden -> list.filterNot { it.id in hidden } }

    /** What the player shows now: another audiobook is read again. */
    fun onState(state: PlayerUiState) {
        current = state
        val id = state.bookId.takeUnless { state.narrating }
        if (id == book.value) return
        book.value = id
        if (id != null) refresh(id)
    }

    override suspend fun addBookmark(): AddOutcome? {
        val state = current
        val id = state.bookId.takeUnless { state.narrating } ?: return null
        val chapter = state.chapters.getOrNull(state.chapterIndex)
        val title = BookmarkPlace.at(chapter, state.bookTimeMs).text
        return scope.async {
            // A failure (the device's store unwritable) is no bookmark, not a crash: the screen says so.
            runCatching { bookmarks.add(id, state.bookTimeMs, chapter?.chapterId, title) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }.await()
    }

    override fun editBookmark(bookmark: AudioBookmark, title: String, note: String?) {
        scope.launch { bookmarks.edit(bookmark.bookId, bookmark.id, title, note) }
    }

    override fun goToBookmark(bookmark: AudioBookmark) = jumpTo(bookmark.positionMs)

    override fun deleteBookmark(bookmark: AudioBookmark) = deleting.update { it + bookmark.id }

    override fun undoDelete(bookmark: AudioBookmark) = deleting.update { it - bookmark.id }

    override fun confirmDelete(bookmark: AudioBookmark) {
        if (bookmark.id !in deleting.value) return
        // Hidden until the deletion shows in the list: it does not come back for a moment.
        scope.launch {
            try {
                bookmarks.delete(bookmark.bookId, bookmark.id)
            } finally {
                deleting.update { it - bookmark.id }
            }
        }
    }

    override fun refreshBookmarks() {
        book.value?.let(::refresh)
    }

    private fun refresh(bookId: BookId) {
        scope.launch { bookmarks.refresh(bookId) }
    }
}
