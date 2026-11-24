package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A highlight made again on the server: in one write, from the values shown at that moment, with
 * every edit made while the request was on its way. And a change that waits for its add.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarksFlusherRemakeTest {

    private val rig = FlusherRig()
    private val repository = rig.repository
    private val marks = rig.marks
    private val h5 = MarkId.ofHighlight(sid(5))
    private val addCall = "addHighlight $Book ${NewOne.cfi}"

    /** Highlight 5, deleted elsewhere, and a first change of it on its way to the server, held at a gate. */
    private suspend fun TestScope.inFlight(first: MarkChange): CompletableDeferred<Unit> {
        rig.onServer(5)
        repository.highlightList.clear()
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        marks.record(first)
        rig.flusher(this).kick()
        runCurrent()
        repository.gate = null
        return gate
    }

    @Test
    fun `a note typed again while the first was on its way is made once, with the last note`() = runTest {
        val gate = inFlight(change(h5, note = NoteEdit.Set("first")))
        marks.record(change(h5, note = NoteEdit.Set("second")))

        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls.filter { it.startsWith("addHighlight") }).hasSize(1)
        assertThat(repository.highlightList.map { it.note }).containsExactly("second")
        assertThat(rig.shown().highlights.map { it.id to it.note }).containsExactly(h5 to "second")
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a note typed while a colour was on its way makes the highlight again`() = runTest {
        val gate = inFlight(change(h5, color = "#38BDF8"))
        marks.record(change(h5, note = NoteEdit.Set("typed")))

        gate.complete(Unit)
        runCurrent()

        assertThat(repository.highlightList.map { it.color to it.note }).containsExactly("#38BDF8" to "typed")
        assertThat(rig.shown().highlights.map { it.note }).containsExactly("typed")
    }

    @Test
    fun `a note typed then cleared while the first was on its way does not make it again`() = runTest {
        val gate = inFlight(change(h5, note = NoteEdit.Set("typed")))
        marks.record(change(h5, note = NoteEdit.Clear))

        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls).containsExactly("changeHighlight $Book 5 color=null note=typed clearNote=false")
        assertThat(rig.shown().highlights).isEmpty()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a highlight deleted here while its note was on its way stays deleted`() = runTest {
        val gate = inFlight(change(h5, note = NoteEdit.Set("typed")))
        marks.record(delete(h5))

        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls).containsExactly("changeHighlight $Book 5 color=null note=typed clearNote=false")
        assertThat(rig.shown().highlights).isEmpty()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `undoing the delete of an add dropped with it, then typing a note, makes it once with the note`() = runTest {
        repository.failure = ApiError.ServerUnreachable
        val flusher = rig.flusher(this)
        marks.record(addHighlight())
        flusher.kick()
        runCurrent()
        repository.failure = null
        marks.record(delete())
        flusher.kick()
        runCurrent()
        assertThat(marks.pending()).isEmpty()

        marks.record(restore())
        marks.record(change(note = NoteEdit.Set("typed")))
        flusher.kick()
        runCurrent()

        assertThat(repository.calls.filter { it == addCall }).hasSize(2)
        assertThat(repository.highlightList.map { it.note }).containsExactly("typed")
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a change queued before its item's add waits for it, then goes in the same pass`() = runTest {
        marks.apply(addHighlight())
        marks.enqueue(change(color = "#38BDF8"))
        marks.enqueue(addHighlight())

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls)
            .containsExactly(addCall, "changeHighlight $Book 1 color=#38BDF8 note=null clearNote=false").inOrder()
        assertThat(marks.pending()).isEmpty()
    }
}
