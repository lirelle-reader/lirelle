package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Wall
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** [MarkOutbox.remake]: a highlight the server does not have, in one write. */
class MarkRemakeTest {

    private val marks = DataStoreMarks(MemoryDataStore(), Wall)
    private val h5 = MarkId.ofHighlight(sid(5))
    private val server = serverHighlight(5, color = "yellow")

    private suspend fun refreshed() = marks.replaceFromServer(Book, emptyList(), listOf(server), ReadFile)

    @Test
    fun `a highlight wanted again is queued as an add of the values shown, without its old server id`() =
        runBlocking<Unit> {
            refreshed()
            marks.record(change(h5, note = NoteEdit.Set("typed")))

            marks.remake(Book, h5, noteTyped = true)

            val yellow = HighlightColour.Yellow.hex
            val add = NewHighlight(server.cfi!!, ReadFile, server.text, yellow, "typed", server.chapterTitle)
            assertThat(marks.pending()).containsExactly(MarkChange.AddHighlight(Book, h5, ReadFile, add))
            assertThat(marks.held(Book).highlights.single().serverId).isNull()
        }

    @Test
    fun `a colour alone is not worth it, the changes go and the highlight is hidden`() = runBlocking<Unit> {
        refreshed()
        marks.record(change(h5, color = "#38BDF8"))

        marks.remake(Book, h5, noteTyped = false)

        assertThat(marks.pending()).isEmpty()
        assertThat(marks.book(Book).first().highlights).isEmpty()
    }

    @Test
    fun `a note typed then cleared is not worth it either`() = runBlocking<Unit> {
        refreshed()
        marks.record(change(h5, note = NoteEdit.Set("typed")))
        marks.record(change(h5, note = NoteEdit.Clear))

        marks.remake(Book, h5, noteTyped = true)

        assertThat(marks.pending()).isEmpty()
        assertThat(marks.book(Book).first().highlights).isEmpty()
    }

    @Test
    fun `a restore is made again even without a note`() = runBlocking<Unit> {
        refreshed()

        marks.remake(Book, h5, restored = true)

        assertThat(marks.pending().map { it::class }).containsExactly(MarkChange.AddHighlight::class)
    }

    @Test
    fun `a highlight not held only loses its changes`() = runBlocking<Unit> {
        marks.enqueue(change(h5, note = NoteEdit.Set("typed")))

        marks.remake(Book, h5, restored = true)

        assertThat(marks.pending()).isEmpty()
        assertThat(marks.held(Book)).isEqualTo(HeldBook())
    }
}
