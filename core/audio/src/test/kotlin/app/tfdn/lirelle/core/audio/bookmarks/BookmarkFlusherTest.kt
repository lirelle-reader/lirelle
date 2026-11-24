package app.tfdn.lirelle.core.audio.bookmarks

import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Add
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Delete
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Edit
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import app.tfdn.lirelle.core.testing.FakeAudioBookmarkRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkFlusherTest {

    private val queue = DataStoreBookmarkQueue(MemoryDataStore())
    private val repository = FakeAudioBookmarkRepository()
    private val policy = RetryPolicy(firstWait = 5.seconds, longestWait = 40.seconds, unclearAttempts = 3)

    private val a = bookmark("a", positionMs = 120_000)
    private val b = bookmark("b", positionMs = 600_000)

    private fun TestScope.flusher() = BookmarkFlusher(queue, repository, backgroundScope, policy)

    private suspend fun shown() = queue.book(Book).first().shown

    @Test
    fun `nothing is sent before the first kick`() = runTest {
        queue.record(Add(a))
        flusher()

        advanceTimeBy(60.seconds)

        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `changes go in the order they were made, and the answers replace them in the list`() = runTest {
        queue.record(Add(a))
        queue.record(Edit(Book, "a", "Renamed", "Typed"))
        queue.record(Add(b))
        queue.stamp(Add(b))
        queue.record(Delete(Book, "b"))

        flusher().kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("create 5 a", "edit 5 a", "create 5 b", "delete 5 b").inOrder()
        assertThat(queue.pending()).isEmpty()
        assertThat(queue.book(Book).first().server).containsExactly(repository.stored.single())
        assertThat(shown().single().title).isEqualTo("Renamed")
    }

    @Test
    fun `without a network the change waits, and goes with the same client id once it is back`() = runTest {
        repository.online = false
        queue.record(Add(a))
        queue.record(Edit(Book, "a", "Renamed", null))
        flusher().kick()
        runCurrent()
        assertThat(repository.calls).containsExactly("create 5 a")

        advanceTimeBy(5.seconds)
        runCurrent()
        repository.online = true
        advanceTimeBy(10.seconds)
        runCurrent()

        assertThat(repository.created.map { it.id }).containsExactly("a", "a", "a")
        assertThat(repository.calls.last()).isEqualTo("edit 5 a")
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `a kick when the network returns cuts the wait short`() = runTest {
        repository.online = false
        queue.record(Add(a))
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        repository.online = true
        flusher.kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("create 5 a", "create 5 a")
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `an add stamped before it goes is never cancelled afterwards`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        queue.record(Add(a))
        flusher().kick()
        runCurrent()

        queue.record(Delete(Book, "a"))
        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls).containsExactly("create 5 a", "delete 5 a").inOrder()
        assertThat(repository.stored).isEmpty()
        assertThat(shown()).isEmpty()
    }

    @Test
    fun `an add at a second already taken goes with its edits, and the list is read again`() = runTest {
        val there = bookmark("there", positionMs = 120_400)
        repository.stored += there
        queue.record(Add(a))
        queue.record(Edit(Book, "a", "Renamed", null))
        queue.record(Add(b))

        flusher().kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("create 5 a", "list 5", "create 5 b").inOrder()
        assertThat(queue.pending()).isEmpty()
        assertThat(shown().map { it.id }).containsExactly("there", "b").inOrder()
    }

    @Test
    fun `a change of a bookmark gone from the server is done, and it leaves the list`() = runTest {
        queue.keepServer(Book, listOf(a, b), 0)
        queue.record(Edit(Book, "a", "Renamed", null))
        queue.record(Delete(Book, "b"))

        flusher().kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("edit 5 a", "delete 5 b").inOrder()
        assertThat(queue.pending()).isEmpty()
        assertThat(shown()).isEmpty()
    }

    @Test
    fun `a failure that does not say whether trying again helps gives up after the policy's attempts`() =
        runTest {
            repository.createResult = ApiResult.Failure(ApiError.Unexpected)
            queue.record(Add(a))
            flusher().kick()

            advanceTimeBy(60.seconds)
            runCurrent()

            assertThat(repository.calls).containsExactly("create 5 a", "create 5 a", "create 5 a")
            assertThat(queue.pending()).isEmpty()
        }

    @Test
    fun `an ended session stops the flush until the next kick`() = runTest {
        repository.createResult = ApiResult.Failure(ApiError.SessionExpired)
        queue.record(Add(a))
        val flusher = flusher()
        flusher.kick()
        advanceTimeBy(60.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(1)

        repository.createResult = null
        flusher.kick()
        runCurrent()

        assertThat(repository.calls).hasSize(2)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `an add sent at once answers the server's bookmark, and none is sent while another change waits`() =
        runTest {
            val flusher = flusher()
            queue.record(Add(a))

            val created = flusher.sendNow(Add(a))

            assertThat(created).isEqualTo(BookmarkCreateResult.Created(repository.stored.single()))
            assertThat(queue.pending()).isEmpty()

            repository.online = false
            queue.record(Add(b))
            assertThat(flusher.sendNow(Add(b))).isNull()
            queue.record(Add(bookmark("c", positionMs = 900_000)))
            assertThat(flusher.sendNow(Add(bookmark("c", positionMs = 900_000)))).isNull()
            assertThat(repository.calls.count { it == "create 5 c" }).isEqualTo(0)
        }

    @Test
    fun `a list read is kept, unless an answer came in meanwhile`() = runTest {
        repository.stored += a
        val flusher = flusher()

        flusher.refresh(Book)
        assertThat(shown()).containsExactly(a)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        backgroundScope.launch { flusher.refresh(Book) }
        runCurrent()
        queue.record(Add(b))
        queue.settle(Add(b), Settled.Holds(b))
        gate.complete(Unit)
        runCurrent()

        assertThat(shown()).containsExactly(a, b).inOrder()
    }
}
