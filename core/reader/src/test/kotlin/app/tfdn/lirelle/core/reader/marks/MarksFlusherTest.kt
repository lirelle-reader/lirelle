package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Local
import app.tfdn.lirelle.core.reader.marks.MarkSamples.LocalBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.OtherBook
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addBookmark
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.change
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Order, retries and kicks of the marks flusher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarksFlusherTest {

    private val rig = FlusherRig()
    private val repository = rig.repository
    private val marks = rig.marks
    private val h5 = MarkId("h5")
    private val h6 = MarkId("h6")
    private val other = MarkId("5d1e9a2c-7b40-4f8e-a3c6-2e9f8b7d1c05")
    private val addCall = "addHighlight $Book ${NewOne.cfi}"
    private val deleteS5 = "deleteHighlight $Book 5"

    @Test
    fun `nothing is sent before the first kick`() = runTest {
        marks.record(addHighlight())
        rig.flusher(this)

        pass(rig, 60.seconds)

        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `changes are sent in order, the server ids bound, and the outbox ends empty`() = runTest {
        rig.onServer(5, 6)
        marks.record(addHighlight())
        marks.record(change(h5, color = "#38BDF8"))
        marks.record(delete(h6))
        marks.record(addBookmark())

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls).containsExactly(
            addCall,
            "changeHighlight $Book 5 color=#38BDF8 note=null clearNote=false",
            "deleteHighlight $Book 6",
            "addBookmark $Book 420 epubcfi(/6/8!/4/2/1:0) Chapter two",
        ).inOrder()
        assertThat(marks.pending()).isEmpty()
        assertThat(rig.shown().highlights.first { it.id == Local }.serverId).isEqualTo(ServerMarkId("1"))
        assertThat(rig.shown().bookmarks.single { it.id == LocalBookmark }.serverId).isEqualTo(ServerMarkId("2"))
    }

    @Test
    fun `a change after the add went is sent with the server's id`() = runTest {
        val flusher = rig.flusher(this)
        marks.record(addHighlight())
        flusher.kick()
        runCurrent()

        marks.record(change(note = NoteEdit.Set("typed")))
        flusher.kick()
        runCurrent()

        assertThat(repository.calls.last()).isEqualTo("changeHighlight $Book 1 color=null note=typed clearNote=false")
        assertThat(repository.highlightList.single().note).isEqualTo("typed")
    }

    @Test
    fun `a change of an item whose add has not been sent yet waits for it`() = runTest {
        repository.failure = ApiError.ServerUnreachable
        val flusher = rig.flusher(this)
        marks.record(addHighlight())
        flusher.kick()
        runCurrent()
        marks.record(change(color = "#38BDF8"))
        flusher.kick()
        runCurrent()
        assertThat(repository.calls.filterNot { it.startsWith("highlights") }.toSet()).containsExactly(addCall)

        repository.failure = null
        pass(rig, 40.seconds)

        assertThat(repository.calls.takeLast(2))
            .containsExactly(addCall, "changeHighlight $Book 1 color=#38BDF8 note=null clearNote=false").inOrder()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `without network a change stays and is tried again after a growing wait`() = runTest {
        retriedWithGrowingWaits(ApiError.ServerUnreachable)
    }

    @Test
    fun `a failing server never drops a change`() = runTest {
        retriedWithGrowingWaits(ApiError.ServerFailed)
    }

    @Test
    fun `a server asking to slow down is tried again later`() = runTest {
        retriedWithGrowingWaits(ApiError.TooManyAttempts)
    }

    private suspend fun TestScope.retriedWithGrowingWaits(error: ApiError) {
        rig.onServer(5)
        repository.failure = error
        marks.record(delete(h5))
        rig.flusher(this).kick()
        runCurrent()
        assertThat(repository.calls).hasSize(1)

        pass(rig, 4.seconds)
        assertThat(repository.calls).hasSize(1)
        pass(rig, 1.seconds)
        assertThat(repository.calls).hasSize(2)
        pass(rig, 9.seconds)
        assertThat(repository.calls).hasSize(2)
        pass(rig, 1.seconds)
        assertThat(repository.calls).hasSize(3)
        // Far beyond what an unclear failure gets: still waiting.
        pass(rig, 600.seconds)
        assertThat(marks.pending()).containsExactly(delete(h5))

        repository.failure = null
        pass(rig, 40.seconds)
        assertThat(repository.calls.last()).isEqualTo(deleteS5)
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a delete of something already gone counts as done`() = runTest {
        rig.onServer(5)
        repository.highlightList.clear()
        marks.record(delete(h5))

        rig.flusher(this).kick()
        pass(rig, 600.seconds)

        assertThat(repository.calls).containsExactly(deleteS5)
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a failure that does not say why is tried a few times, then the change is dropped`() = runTest {
        rig.onServer(5)
        repository.failure = ApiError.Unexpected
        marks.record(delete(h5))

        rig.flusher(this).kick()
        pass(rig, 600.seconds)

        assertThat(repository.calls).containsExactly(deleteS5, deleteS5, deleteS5)
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `an ended session keeps everything and stops until the next kick`() = runTest {
        rig.onServer(5, 6)
        repository.failure = ApiError.SessionExpired
        marks.record(delete(h5))
        marks.record(delete(h6))
        val flusher = rig.flusher(this)

        flusher.kick()
        pass(rig, 600.seconds)
        assertThat(repository.calls).containsExactly(deleteS5)
        assertThat(marks.pending()).containsExactly(delete(h5), delete(h6)).inOrder()

        repository.failure = null
        flusher.kick()
        runCurrent()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a kick during a flush does not start a second one`() = runTest {
        rig.onServer(5)
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        marks.record(delete(h5))
        val flusher = rig.flusher(this)
        flusher.kick()
        runCurrent()

        flusher.kick()
        flusher.kick()
        runCurrent()
        assertThat(repository.calls).containsExactly(deleteS5)

        gate.complete(Unit)
        runCurrent()
        assertThat(repository.calls).containsExactly(deleteS5)
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a change of an item never sent whose add is gone is dropped`() = runTest {
        marks.apply(addHighlight())
        marks.record(change(color = "#38BDF8"))
        marks.record(delete(other))

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls).isEmpty()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `the books whose changes went are told, once per pass, for their lists to be read again`() = runTest {
        rig.onServer(5)
        val flusher = rig.flusher(this)
        marks.record(delete(h5))
        marks.record(addBookmark(bookId = OtherBook))
        marks.record(addHighlight())
        flusher.kick()
        runCurrent()
        assertThat(rig.sentBooks).containsExactly(Book, OtherBook)

        marks.apply(addHighlight(id = other))
        marks.record(delete(other))
        flusher.kick()
        runCurrent()
        assertThat(rig.sentBooks).containsExactly(Book, OtherBook)
    }
}
