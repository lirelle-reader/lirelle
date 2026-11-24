package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Each change of the facade is shown at once and queued; undo before and after sending. */
class DefaultMarksTest {

    private val rig = FlusherRig()
    private val marks = rig.marks
    private var ids = 0
    private val h5 = MarkId("h5")

    /** A flusher whose scope never runs: what is queued stays there for the test to see. */
    private val idle = MarksFlusher(
        marks, marks, rig.repository, CoroutineScope(StandardTestDispatcher()), rig.clocks.wall,
    )
    private val facade = DefaultMarks(
        outbox = marks,
        store = marks,
        refresher = MarksRefresher(marks, rig.repository, rig.clocks.monotonic),
        flusher = idle,
        newId = { "id-${++ids}" },
    )

    private suspend fun shown() = facade.of(Book).first()

    private suspend fun onServer() =
        marks.replaceFromServer(Book, emptyList(), listOf(serverHighlight(5, note = "old")), ReadFile)

    @Test
    fun `a new highlight shows at once and its add is queued, a blank note left out`() = runTest {
        val id = facade.addHighlight(Book, NewOne.copy(note = " "))

        assertThat(id).isEqualTo(MarkId("id-1"))
        assertThat(shown().highlights.map { it.id to it.note }).containsExactly(id to null)
        assertThat(marks.pending()).containsExactly(MarkChange.AddHighlight(Book, id, ReadFile, NewOne))
    }

    @Test
    fun `a colour and a note show at once and are queued as one change, a blank note clearing it`() = runTest {
        onServer()

        facade.recolour(Book, h5, "#38BDF8")
        facade.setNote(Book, h5, "typed")
        assertThat(shown().highlights.single().let { it.color to it.note }).isEqualTo("#38BDF8" to "typed")

        facade.setNote(Book, h5, "  ")
        assertThat(shown().highlights.single().note).isNull()
        assertThat(marks.pending()).containsExactly(change(h5, color = "#38BDF8", note = NoteEdit.Clear))
    }

    @Test
    fun `a deleted highlight goes at once, and undoing a delete not yet sent leaves nothing queued`() = runTest {
        onServer()

        facade.deleteHighlight(Book, h5)
        assertThat(shown().highlights).isEmpty()
        assertThat(marks.pending()).containsExactly(delete(h5))

        facade.undoDeleteHighlight(Book, h5)
        assertThat(shown().highlights.single().note).isEqualTo("old")
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `undoing a delete already sent queues a restore and shows the highlight at once`() = runTest {
        onServer()
        facade.deleteHighlight(Book, h5)
        marks.markSent(delete(h5))

        facade.undoDeleteHighlight(Book, h5)

        assertThat(marks.pending()).containsExactly(restore(h5))
        assertThat(shown().highlights.single().let { it.id to it.note }).isEqualTo(h5 to "old")
    }

    @Test
    fun `undoing the delete of a highlight never sent brings its add back`() = runTest {
        val id = facade.addHighlight(Book, NewOne)
        facade.deleteHighlight(Book, id)
        assertThat(marks.pending()).isEmpty()

        facade.undoDeleteHighlight(Book, id)

        assertThat(marks.pending()).containsExactly(MarkChange.AddHighlight(Book, id, ReadFile, NewOne))
        assertThat(shown().highlights.map { it.id }).containsExactly(id)
    }

    @Test
    fun `bookmarks show and go at once, and undoing a delete adds the same place and title again`() = runTest {
        val cfi = "epubcfi(/6/8!/4/2/1:0)"
        val first = facade.addBookmark(Book, ReadFile, cfi, "Chapter two")
        val second = facade.addBookmark(Book, ReadFile, "epubcfi(/6/10!/4/2/1:0)", "Chapter three")
        val added = MarkChange.AddBookmark(Book, first, ReadFile, cfi, "Chapter two")
        assertThat(shown().bookmarks.map { it.id }).containsExactly(first, second).inOrder()
        assertThat(marks.pending().first()).isEqualTo(added)
        marks.pending().forEach { marks.markSent(it) }
        marks.bindServerId(Book, first, sid(3))

        facade.deleteBookmarks(Book, listOf(first, second))
        assertThat(shown().bookmarks).isEmpty()
        assertThat(marks.pending())
            .containsExactly(MarkChange.DeleteBookmark(Book, first), MarkChange.DeleteBookmark(Book, second))
        marks.pending().forEach { marks.markSent(it) }

        facade.undoDeleteBookmarks(Book, listOf(first))
        assertThat(marks.pending()).containsExactly(added)
        assertThat(shown().bookmarks.map { it.id to it.serverId }).containsExactly(first to sid(3))
    }

    @Test
    fun `a new highlight is made in the file, the colour and with the note given`() = runTest {
        val given = NewHighlight(NewOne.cfi, FileId("77"), NewOne.text, "#F472B6", "a thought", null)

        val id = facade.addHighlight(Book, given)

        assertThat(marks.pending()).containsExactly(MarkChange.AddHighlight(Book, id, FileId("77"), given))
        assertThat(marks.held(Book).fileId).isEqualTo(FileId("77"))
    }
}
