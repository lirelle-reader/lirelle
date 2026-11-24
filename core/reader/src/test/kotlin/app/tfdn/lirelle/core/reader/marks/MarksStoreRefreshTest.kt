package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Local
import app.tfdn.lirelle.core.reader.marks.MarkSamples.OtherBook
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Wall
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import app.tfdn.lirelle.core.reader.sync.DataStores
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** The server's lists replace what the store holds, except for items with changes still waiting. */
class MarksStoreRefreshTest {

    @get:Rule
    val dataStores = DataStores()

    private val dataStore by lazy { dataStores.create() }
    private val marks by lazy { DataStoreMarks(dataStore, Wall) }
    private val store: MarksStore get() = marks
    private val outbox: MarkOutbox get() = marks

    /** What the facade does: shown and queued in one write. */
    private suspend fun local(change: MarkChange) = outbox.record(change)

    private suspend fun shown() = store.book(Book).first()

    private suspend fun refresh(vararg highlights: Long, bookmarks: List<Long> = emptyList()) =
        store.replaceFromServer(Book, bookmarks.map { serverBookmark(it) }, highlights.map { serverHighlight(it) })

    @Test
    fun `server items are shown under ids of their own`() = runBlocking<Unit> {
        store.replaceFromServer(Book, listOf(serverBookmark(4)), listOf(serverHighlight(3, note = "kept")))

        val server = serverHighlight(3, note = "kept")
        val bookmark = serverBookmark(4)
        assertThat(shown()).isEqualTo(
            BookMarks(
                bookmarks = listOf(
                    Bookmark(
                        MarkId("b4"), sid(4), bookmark.cfi, bookmark.title, null, bookmark.origin, bookmark.createdAt,
                    ),
                ),
                highlights = listOf(
                    Highlight(
                        MarkId("h3"), sid(3), server.cfi, server.text, server.color, server.style, "kept",
                        server.chapterTitle, server.chapterIndex, server.origin, server.positionStatus,
                        server.createdAt,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the server's lists replace what was there`() = runBlocking<Unit> {
        refresh(1, 2, bookmarks = listOf(4, 5))

        val renamed = serverBookmark(5, title = "Renamed")
        store.replaceFromServer(Book, listOf(renamed), listOf(serverHighlight(2, color = "#F472B6")))

        assertThat(shown().highlights.map { it.id to it.color }).containsExactly(MarkId("h2") to "#F472B6")
        assertThat(shown().bookmarks.map { it.id to it.title }).containsExactly(MarkId("b5") to "Renamed")
    }

    @Test
    fun `an item with a change waiting keeps its local state`() = runBlocking<Unit> {
        refresh(1, 2)
        local(change(MarkId("h1"), color = "#38BDF8"))
        local(delete(MarkId("h2")))

        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(1, note = "elsewhere"), serverHighlight(2)))

        assertThat(shown().highlights.map { it.id to it.color }).containsExactly(MarkId("h1") to "#38BDF8")
        assertThat(shown().highlights.single().note).isNull()
    }

    @Test
    fun `an item gone from the server stays while a change of it waits`() = runBlocking<Unit> {
        refresh(1, 2)
        local(change(MarkId("h1"), note = NoteEdit.Set("typed")))
        local(addHighlight())
        local(addBookmark())

        refresh()

        assertThat(shown().highlights.map { it.id }).containsExactly(MarkId("h1"), Local)
        assertThat(shown().bookmarks.map { it.id }).hasSize(1)
    }

    @Test
    fun `once sent, the server's state wins again`() = runBlocking<Unit> {
        refresh(1)
        local(change(MarkId("h1"), color = "#38BDF8"))
        outbox.markSent(outbox.pending().single())

        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(1, color = "#F472B6")))

        assertThat(shown().highlights.map { it.color }).containsExactly("#F472B6")
    }

    @Test
    fun `a refresh between enqueue and apply keeps the local state`() = runBlocking<Unit> {
        refresh(1)
        outbox.record(addHighlight())
        outbox.enqueue(change(MarkId("h1"), color = "#38BDF8"))
        outbox.enqueue(change(Local, color = "#F472B6"))

        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(1, note = "elsewhere")))
        store.apply(change(MarkId("h1"), color = "#38BDF8"))
        store.apply(change(Local, color = "#F472B6"))

        assertThat(shown().highlights.map { Triple(it.id, it.color, it.note) })
            .containsExactly(Triple(MarkId("h1"), "#38BDF8", null), Triple(Local, "#F472B6", null))
    }

    @Test
    fun `a change applied but never queued does not hold back the server's state`() = runBlocking<Unit> {
        refresh(1)
        store.apply(change(MarkId("h1"), color = "#38BDF8"))
        store.apply(addHighlight())

        refresh(1)

        assertThat(shown().highlights.map { it.id to it.color }).containsExactly(MarkId("h1") to "#4ADE80")
    }

    @Test
    fun `record shows the change and queues it`() = runBlocking<Unit> {
        refresh(1)

        outbox.record(change(MarkId("h1"), color = "#38BDF8"))

        assertThat(shown().highlights.map { it.color }).containsExactly("#38BDF8")
        assertThat(outbox.pending()).containsExactly(change(MarkId("h1"), color = "#38BDF8"))
    }

    @Test
    fun `changes cancelled in the queue no longer hold the server's state back`() = runBlocking<Unit> {
        refresh(1)
        local(delete(MarkId("h1")))
        local(restore(MarkId("h1")))

        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(1, note = "elsewhere")))

        assertThat(shown().highlights.map { it.note }).containsExactly("elsewhere")
    }

    @Test
    fun `a bound item keeps its id through a refresh`() = runBlocking<Unit> {
        local(addHighlight())
        store.bindServerId(Book, Local, sid(31))
        outbox.markSent(outbox.pending().single())

        store.replaceFromServer(Book, emptyList(), listOf(serverHighlight(31, note = "elsewhere")))

        assertThat(shown().highlights.map { it.id to it.note }).containsExactly(Local to "elsewhere")
    }

    @Test
    fun `an add that never reached the server goes once nothing waits for it`() = runBlocking<Unit> {
        local(addHighlight())
        outbox.markSent(outbox.pending().single())

        refresh()

        assertThat(shown().highlights).isEmpty()
    }

    @Test
    fun `a delete sent and refreshed can still be undone at once`() = runBlocking<Unit> {
        refresh(1, bookmarks = listOf(4))
        local(change(MarkId("h1"), note = NoteEdit.Set("typed")))
        outbox.markSent(outbox.pending().single())
        local(delete(MarkId("h1")))
        local(deleteBookmark(MarkId("b4")))
        outbox.pending().forEach { outbox.markSent(it) }
        refresh()

        local(restore(MarkId("h1")))

        assertThat(shown().highlights.map { it.id to it.note }).containsExactly(MarkId("h1") to "typed")
        assertThat(shown().bookmarks).isEmpty()
    }

    @Test
    fun `a refresh leaves other books alone`() = runBlocking<Unit> {
        store.replaceFromServer(OtherBook, emptyList(), listOf(serverHighlight(9)))

        refresh(1)

        assertThat(store.book(OtherBook).first().highlights.map { it.id }).containsExactly(MarkId("h9"))
    }

    @Test
    fun `beyond 30 books, the least recently written without a change waiting are dropped`() = runBlocking<Unit> {
        local(addHighlight(bookId = book(1)))
        (1L..30L).forEach { store.replaceFromServer(book(it), emptyList(), listOf(serverHighlight(it))) }
        store.replaceFromServer(book(2), emptyList(), listOf(serverHighlight(2)))

        store.replaceFromServer(book(31), emptyList(), listOf(serverHighlight(31)))
        store.replaceFromServer(book(32), emptyList(), listOf(serverHighlight(32)))

        val kept = (1L..32L).map(::book).filter { store.book(it).first().highlights.isNotEmpty() }
        assertThat(kept).containsExactlyElementsIn((listOf(1L, 2L) + (5L..32L)).map(::book))
        assertThat(store.book(book(1)).first().highlights.map { it.id }).containsExactly(Local, MarkId("h1"))
    }

    /** The [n]th book written: its id is a text of its own, the order is that of the writes. */
    private fun book(n: Long) = BookId("book-$n")

}
