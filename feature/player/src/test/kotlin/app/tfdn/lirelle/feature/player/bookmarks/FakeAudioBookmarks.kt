package app.tfdn.lirelle.feature.player.bookmarks

import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.audio.bookmarks.AudioBookmarks
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The bookmarks of each book in memory, with what was asked of them, each as a short line. */
class FakeAudioBookmarks : AudioBookmarks {
    val lists = MutableStateFlow<Map<BookId, List<AudioBookmark>>>(emptyMap())
    val refreshed = mutableListOf<BookId>()
    val asked = mutableListOf<String>()

    /** What an add answers, given the bookmark it would add; by default it is added. */
    var answer: (AudioBookmark) -> AddOutcome = { AddOutcome.Added(it) }

    /** What an add or a delete throws instead, if anything. */
    var addFailure: Throwable? = null
    var deleteFailure: Throwable? = null

    override fun of(bookId: BookId): Flow<List<AudioBookmark>> = lists.map { it[bookId].orEmpty() }

    override suspend fun refresh(bookId: BookId) {
        refreshed += bookId
    }

    override suspend fun add(bookId: BookId, positionMs: Long, chapterId: String?, title: String): AddOutcome {
        asked += "add $bookId $positionMs $chapterId $title"
        addFailure?.let { throw it }
        val bookmark = AudioBookmark("new-${asked.size}", bookId, positionMs, chapterId, title, null, null, null)
        return answer(bookmark).also { outcome ->
            if (outcome is AddOutcome.Added) put(bookId) { (it + bookmark).sortedBy(AudioBookmark::positionMs) }
        }
    }

    override suspend fun edit(bookId: BookId, id: String, title: String, note: String?) {
        asked += "edit $bookId $id $title $note"
    }

    override suspend fun delete(bookId: BookId, id: String) {
        asked += "delete $bookId $id"
        deleteFailure?.let { throw it }
        put(bookId) { list -> list.filterNot { it.id == id } }
    }

    fun put(bookId: BookId, change: (List<AudioBookmark>) -> List<AudioBookmark>) =
        lists.update { it + (bookId to change(it[bookId].orEmpty())) }

    companion object {
        fun bookmark(
            id: String,
            positionMs: Long,
            title: String = "Bookmark $id",
            note: String? = null,
            bookId: BookId = BookId("42"),
        ) = AudioBookmark(id, bookId, positionMs, null, title, note, null, null)
    }
}
