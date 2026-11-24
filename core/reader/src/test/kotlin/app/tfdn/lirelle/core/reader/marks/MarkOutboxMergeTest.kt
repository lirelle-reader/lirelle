package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.reader.marks.MarkSamples.At
import app.tfdn.lirelle.core.reader.marks.MarkSamples.LocalBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.OtherBook
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Wall
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.deleteBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.sync.DataStores
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** How changes of one item waiting together are merged, and what happens when one was being sent. */
class MarkOutboxMergeTest {

    @get:Rule
    val dataStores = DataStores()

    private val server = MarkId("h5")
    private val outbox by lazy { DataStoreMarks(dataStores.create(), Wall) }

    private suspend fun enqueue(vararg changes: MarkChange) = changes.forEach { outbox.enqueue(it) }

    @Test
    fun `an add then changes is one add with the final colour and note`() = runBlocking<Unit> {
        enqueue(addHighlight(), change(color = "#38BDF8"), change(note = NoteEdit.Set("first")))
        enqueue(change(color = "#F472B6", note = NoteEdit.Set("second")))

        assertThat(outbox.pending())
            .containsExactly(addHighlight(highlight = NewOne.copy(color = "#F472B6", note = "second")))
    }

    @Test
    fun `a cleared note leaves the add without one`() = runBlocking<Unit> {
        enqueue(addHighlight(highlight = NewOne.copy(note = "typed")), change(note = NoteEdit.Clear))

        assertThat(outbox.pending()).containsExactly(addHighlight(highlight = NewOne.copy(note = null)))
    }

    @Test
    fun `an add then a delete is nothing`() = runBlocking<Unit> {
        enqueue(addHighlight(), change(color = "#38BDF8"), delete())

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `two changes are one, a later field winning`() = runBlocking<Unit> {
        enqueue(change(server, color = "#38BDF8", note = NoteEdit.Set("first")), change(server, color = "#F472B6"))
        enqueue(change(server, note = NoteEdit.Clear))

        assertThat(outbox.pending()).containsExactly(change(server, color = "#F472B6", note = NoteEdit.Clear))
    }

    @Test
    fun `a change then a delete is the delete`() = runBlocking<Unit> {
        enqueue(change(server, note = NoteEdit.Set("typed")), delete(server))

        assertThat(outbox.pending()).containsExactly(delete(server))
    }

    @Test
    fun `a change after a delete is dropped`() = runBlocking<Unit> {
        enqueue(delete(server), change(server, color = "#38BDF8"))

        assertThat(outbox.pending()).containsExactly(delete(server))
    }

    @Test
    fun `a restore of a delete already sent stays, after it`() = runBlocking<Unit> {
        enqueue(delete(server))
        assertThat(outbox.pending()).containsExactly(delete(server))
        outbox.markSent(delete(server))

        enqueue(restore(server))

        assertThat(outbox.pending()).containsExactly(restore(server))
    }

    @Test
    fun `a restore of a delete not sent yet is nothing`() = runBlocking<Unit> {
        enqueue(delete(server), restore(server))

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `a delete of a restore not sent yet is nothing`() = runBlocking<Unit> {
        enqueue(restore(server), change(server, note = NoteEdit.Set("typed")), delete(server))

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `a restore after an add that a delete cancelled brings the add back`() = runBlocking<Unit> {
        enqueue(addHighlight(), delete(), restore())

        assertThat(outbox.pending()).containsExactly(addHighlight())
    }

    @Test
    fun `an add then a delete of a bookmark is nothing`() = runBlocking<Unit> {
        enqueue(addBookmark(), deleteBookmark())

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `a bookmark wanted again after a delete not sent yet is only added`() = runBlocking<Unit> {
        val serverBookmark = MarkId("b9")
        enqueue(deleteBookmark(serverBookmark), addBookmark(serverBookmark))

        assertThat(outbox.pending()).containsExactly(addBookmark(serverBookmark))
    }

    @Test
    fun `the order is kept across items and books`() = runBlocking<Unit> {
        val other = MarkId("h6")
        enqueue(addHighlight(), change(other, bookId = OtherBook, color = "#38BDF8"), addBookmark(), delete(server))
        enqueue(change(color = "#4ADE80"))

        assertThat(outbox.pending()).containsExactly(
            addHighlight(highlight = NewOne.copy(color = "#4ADE80")),
            change(other, bookId = OtherBook, color = "#38BDF8"),
            addBookmark(),
            delete(server),
        ).inOrder()
    }

    @Test
    fun `the same id in another book is another item`() = runBlocking<Unit> {
        enqueue(addHighlight(), delete(bookId = OtherBook))

        assertThat(outbox.pending()).containsExactly(addHighlight(), delete(bookId = OtherBook)).inOrder()
    }

    @Test
    fun `an add that may have reached the server keeps its changes and its delete apart`() = runBlocking<Unit> {
        val attempted = addHighlight().copy(attemptedAt = At)
        enqueue(addHighlight())
        outbox.replace(addHighlight(), attempted)

        enqueue(change(color = "#38BDF8"), delete())

        assertThat(outbox.pending()).containsExactly(attempted, delete()).inOrder()
    }

    @Test
    fun `replace swaps a change still stored, and nothing else`() = runBlocking<Unit> {
        val attempted = addHighlight().copy(attemptedAt = At)
        enqueue(addHighlight(), addBookmark())

        outbox.replace(addHighlight(), attempted)
        outbox.replace(delete(server), restore(server))

        assertThat(outbox.pending()).containsExactly(attempted, addBookmark()).inOrder()
    }

    @Test
    fun `what was sent is removed, and only that`() = runBlocking<Unit> {
        enqueue(addHighlight(), addBookmark(), delete(server))

        outbox.markSent(addBookmark())
        outbox.markSent(deleteBookmark(LocalBookmark))

        assertThat(outbox.pending()).containsExactly(addHighlight(), delete(server)).inOrder()
    }
}
