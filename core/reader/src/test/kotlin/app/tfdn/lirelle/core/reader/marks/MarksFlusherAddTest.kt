package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Book
import app.tfdn.lirelle.core.reader.marks.MarkSamples.Local
import app.tfdn.lirelle.core.reader.marks.MarkSamples.NewOne
import app.tfdn.lirelle.core.reader.marks.MarkSamples.addHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.delete
import app.tfdn.lirelle.core.reader.marks.MarkSamples.restore
import app.tfdn.lirelle.core.reader.marks.MarkSamples.serverHighlight
import app.tfdn.lirelle.core.reader.marks.MarkSamples.sid
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The duplicate guard: an add is stamped before it is sent, and one whose answer was lost is
 * looked for on the server before it is sent again. Changes cancelled while in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarksFlusherAddTest {

    private val rig = FlusherRig()
    private val repository = rig.repository
    private val marks = rig.marks
    private val addCall = "addHighlight $Book ${NewOne.cfi}"
    private val listCall = "highlights $Book 420"
    private val other = MarkId("5d1e9a2c-7b40-4f8e-a3c6-2e9f8b7d1c05")

    /** What the server holds when an add landed but its answer was lost. */
    private fun landed(id: Long, createdAt: Instant) = serverHighlight(id).copy(
        cfi = NewOne.cfi, text = NewOne.text, color = NewOne.color, createdAt = createdAt,
    )

    /** Kicks a send of the add that fails without network: its answer, for all we know, was lost. */
    private fun TestScope.lostAnswer(): MarksFlusher {
        repository.failure = ApiError.ServerUnreachable
        val flusher = rig.flusher(this)
        flusher.kick()
        runCurrent()
        repository.failure = null
        return flusher
    }

    @Test
    fun `an add is stamped before its send, and sent only as the outbox gives it back stamped`() = runTest {
        val stampedAt = rig.clocks.now
        repository.gate = CompletableDeferred()
        marks.record(addHighlight())

        rig.flusher(this).kick()
        runCurrent()

        assertThat(repository.calls).containsExactly(addCall)
        assertThat(marks.pending()).containsExactly(addHighlight().copy(attemptedAt = stampedAt))
    }

    @Test
    fun `an add deleted while it is being sent is deleted on the server after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        marks.record(addHighlight())
        rig.flusher(this).kick()
        runCurrent()

        marks.record(delete())
        repository.gate = null
        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls).containsExactly(addCall, "deleteHighlight $Book 1").inOrder()
        assertThat(repository.highlightList).isEmpty()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `an add deleted between the reading of the queue and its stamp is not sent at all`() = runTest {
        var deleted = false
        rig.outbox = object : MarkOutbox by marks {
            override suspend fun replace(old: MarkChange, new: MarkChange) {
                if (!deleted) marks.record(delete())
                deleted = true
                marks.replace(old, new)
            }
        }
        marks.record(addHighlight())

        rig.flusher(this).kick()
        pass(rig, 600.seconds)

        assertThat(repository.calls).isEmpty()
        assertThat(marks.pending()).isEmpty()
        assertThat(rig.shown().highlights).isEmpty()
    }

    @Test
    fun `a stamped add keeps its first stamp when it is tried again`() = runTest {
        val stampedAt = rig.clocks.now
        repository.failure = ApiError.ServerUnreachable
        marks.record(addHighlight())
        rig.flusher(this).kick()
        runCurrent()

        pass(rig, 5.seconds)
        pass(rig, 10.seconds)

        assertThat(repository.calls).containsExactly(addCall, listCall, listCall)
        assertThat(marks.pending()).containsExactly(addHighlight().copy(attemptedAt = stampedAt))
    }

    @Test
    fun `an add whose answer was lost is found on the server and bound, not sent again`() = runTest {
        marks.record(addHighlight())
        lostAnswer()
        // It landed after all, by a server clock a minute behind.
        repository.highlightList += landed(9, rig.clocks.now.minusSeconds(60))

        pass(rig, 5.seconds)

        assertThat(repository.calls).containsExactly(addCall, listCall).inOrder()
        assertThat(repository.highlightList).hasSize(1)
        assertThat(rig.shown().highlights.single().serverId).isEqualTo(sid(9))
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `the same text highlighted well before the send is not taken for it`() = runTest {
        repository.highlightList += landed(9, rig.clocks.now.minusSeconds(121))
        marks.record(addHighlight())
        lostAnswer()

        pass(rig, 5.seconds)

        assertThat(repository.calls).containsExactly(addCall, listCall, addCall).inOrder()
        assertThat(rig.shown().highlights.single().serverId).isEqualTo(sid(1))
    }

    @Test
    fun `a highlight bound to another item here is not taken for it, one a refresh brought as h-id is`() = runTest {
        marks.apply(addHighlight(id = other))
        marks.bindServerId(Book, other, sid(9))
        repository.highlightList += landed(9, rig.clocks.now)
        marks.record(addHighlight())
        lostAnswer()
        pass(rig, 5.seconds)
        assertThat(repository.calls.last()).isEqualTo(addCall)

        val refreshed = FlusherRig()
        refreshed.repository.highlightList += landed(9, refreshed.clocks.now)
        refreshed.marks.replaceFromServer(Book, emptyList(), refreshed.repository.highlightList.toList())
        refreshed.marks.record(addHighlight())
        refreshed.repository.failure = ApiError.ServerUnreachable
        refreshed.flusher(this).kick()
        runCurrent()
        refreshed.repository.failure = null
        pass(refreshed, 5.seconds)

        assertThat(refreshed.repository.calls).containsExactly(addCall, listCall).inOrder()
        assertThat(refreshed.shown().highlights.map { it.id to it.serverId }).containsExactly(Local to sid(9))
    }

    @Test
    fun `an add tried, then deleted, is dropped with its delete when the server does not have it`() = runTest {
        marks.record(addHighlight())
        val flusher = lostAnswer()
        marks.record(delete())

        flusher.kick()
        runCurrent()

        assertThat(repository.calls).containsExactly(addCall, listCall).inOrder()
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `undoing the delete of an add dropped with it sends the add again`() = runTest {
        marks.record(addHighlight())
        val flusher = lostAnswer()
        marks.record(delete())
        flusher.kick()
        runCurrent()

        marks.record(restore())
        flusher.kick()
        runCurrent()

        assertThat(repository.calls).containsExactly(addCall, listCall, addCall).inOrder()
        assertThat(rig.shown().highlights.single().serverId).isEqualTo(sid(1))
        assertThat(marks.pending()).isEmpty()
    }

    @Test
    fun `a delete cancelled while in flight is undone on the server after it`() = runTest {
        rig.onServer(5)
        val h5 = MarkId("h5")
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        repository.failure = ApiError.ServerUnreachable
        marks.record(delete(h5))
        rig.flusher(this).kick()
        runCurrent()

        marks.record(restore(h5))
        repository.gate = null
        gate.complete(Unit)
        runCurrent()
        assertThat(marks.pending()).containsExactly(restore(h5))

        repository.failure = null
        pass(rig, 5.seconds)
        assertThat(repository.calls)
            .containsExactly("deleteHighlight $Book 5", "restoreHighlight 5", "restoreHighlight 5").inOrder()
        assertThat(marks.pending()).isEmpty()
        assertThat(rig.shown().highlights.map { it.id }).containsExactly(h5)
    }
}
