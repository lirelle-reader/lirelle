package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.reader.marks.MarkSamples.At
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

/**
 * A change cancelled while the flusher was sending it: the server may have taken it, so when the
 * flusher reports it (sent, or its answer lost), the change that undoes it is queued first.
 */
class MarkOutboxInFlightTest {

    @get:Rule
    val dataStores = DataStores()

    private val server = MarkId("h5")
    private val outbox by lazy { DataStoreMarks(dataStores.create(), Wall) }

    @Test
    fun `a bookmark added while a delete cancelled it is deleted after all`() = runBlocking<Unit> {
        outbox.enqueue(addBookmark())
        val sending = outbox.pending().single()
        outbox.enqueue(deleteBookmark())

        outbox.markSent(sending)

        assertThat(outbox.pending()).containsExactly(deleteBookmark())
    }

    @Test
    fun `a delete sent while a restore cancelled it is restored first`() = runBlocking<Unit> {
        outbox.enqueue(delete(server))
        val sending = outbox.pending().single()
        outbox.enqueue(restore(server))
        outbox.enqueue(change(server, note = NoteEdit.Set("typed")))

        outbox.markSent(sending)

        assertThat(outbox.pending()).containsExactly(restore(server), change(server, note = NoteEdit.Set("typed")))
            .inOrder()
    }

    @Test
    fun `a delete whose answer was lost after a restore cancelled it is restored`() = runBlocking<Unit> {
        outbox.enqueue(delete(server))
        val sending = outbox.pending().single()
        outbox.enqueue(restore(server))

        // The send failed in a way that says nothing, and the delete is no longer waiting: it may have left.
        assertThat(outbox.pending()).doesNotContain(sending)
        outbox.markSent(sending)

        assertThat(outbox.pending()).containsExactly(restore(server))
    }

    @Test
    fun `a cancelled change that was never being sent leaves nothing`() = runBlocking<Unit> {
        outbox.enqueue(delete(server))
        outbox.enqueue(restore(server))

        outbox.enqueue(delete(server))
        outbox.markSent(delete(server))

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `an add stamped before its first send keeps its delete apart, and is removed once sent`() =
        runBlocking<Unit> {
            outbox.enqueue(addHighlight())
            val stamped = addHighlight().copy(attemptedAt = At)
            outbox.replace(addHighlight(), stamped)
            outbox.enqueue(change(color = "#38BDF8"))
            outbox.enqueue(delete())

            outbox.markSent(outbox.pending().first())

            assertThat(outbox.pending()).containsExactly(delete())
        }

    @Test
    fun `an add cancelled before it was stamped is not stamped back`() = runBlocking<Unit> {
        outbox.enqueue(addHighlight())
        val read = outbox.pending().single() as MarkChange.AddHighlight
        outbox.enqueue(delete())

        outbox.replace(read, read.copy(attemptedAt = At))

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `an add changed before it was stamped is not stamped`() = runBlocking<Unit> {
        outbox.enqueue(addHighlight())
        val read = outbox.pending().single() as MarkChange.AddHighlight
        outbox.enqueue(change(color = "#38BDF8"))

        outbox.replace(read, read.copy(attemptedAt = At))

        assertThat((outbox.pending().single() as MarkChange.AddHighlight).attemptedAt).isNull()
    }
}
