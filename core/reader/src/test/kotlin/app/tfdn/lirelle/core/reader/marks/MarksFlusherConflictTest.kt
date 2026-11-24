package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.ReadFile
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** An item deleted elsewhere while it was changed here. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarksFlusherConflictTest {

    private val rig = FlusherRig()
    private val repository = rig.repository
    private val marks = rig.marks
    private val h5 = MarkId("h5")
    private val original = serverHighlight(5)

    @Test
    fun `a note typed on a highlight deleted elsewhere makes it again with the values shown`() = runTest {
        rig.onServer(5)
        repository.highlightList.clear()
        marks.record(change(h5, color = "#38BDF8", note = NoteEdit.Set("typed")))

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls).containsExactly(
            "changeHighlight $Book 5 color=#38BDF8 note=typed clearNote=false",
            "addHighlight $Book ${original.cfi}",
        ).inOrder()
        val made = repository.highlightList.single()
        assertThat(NewHighlight(made.cfi!!, ReadFile, made.text, made.color, made.note, made.chapterTitle))
            .isEqualTo(NewHighlight(original.cfi!!, ReadFile, original.text, "#38BDF8", "typed", original.chapterTitle))
        assertThat(rig.shown().highlights.map { Triple(it.id, it.serverId, it.note) })
            .containsExactly(Triple(h5, made.id, "typed"))
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a colour changed on a highlight deleted elsewhere is dropped, and the highlight goes`() = runTest {
        rig.onServer(5)
        repository.highlightList.clear()
        marks.record(change(h5, color = "#38BDF8"))

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls)
            .containsExactly("changeHighlight $Book 5 color=#38BDF8 note=null clearNote=false")
        assertThat(rig.shown().highlights).isEmpty()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a cleared note on a highlight deleted elsewhere is dropped like a colour`() = runTest {
        rig.onServer(5)
        repository.highlightList.clear()
        marks.record(change(h5, note = NoteEdit.Clear))

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls).hasSize(1)
        assertThat(rig.shown().highlights).isEmpty()
    }

    @Test
    fun `a restore of a highlight gone from the trash counts as done`() = runTest {
        rig.onServer(5)
        repository.highlightList.clear()
        marks.record(MarkSamples.delete(h5))
        marks.markSent(MarkSamples.delete(h5))
        marks.record(restore(h5))

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("restoreHighlight 5")
        assertThat(marks.pending()).isEmpty()
    }
}
