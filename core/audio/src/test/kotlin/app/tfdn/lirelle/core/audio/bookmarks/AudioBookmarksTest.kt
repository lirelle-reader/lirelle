package app.tfdn.lirelle.core.audio.bookmarks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Add
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Delete
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Edit
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import app.tfdn.lirelle.core.testing.FakeAudioBookmarkRepository
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioBookmarksTest {

    private val dataStore = MemoryDataStore()
    private val repository = FakeAudioBookmarkRepository()
    private val policy = RetryPolicy(firstWait = 5.seconds, longestWait = 40.seconds, unclearAttempts = 3)
    private val clock = Clock.fixed(Made, ZoneOffset.UTC)
    private var ids = 0

    private fun queue(store: DataStore<Preferences> = dataStore) = DataStoreBookmarkQueue(store)

    private fun TestScope.bookmarks(queue: BookmarkQueue = queue()) = DefaultAudioBookmarks(
        queue = queue,
        flusher = BookmarkFlusher(queue, repository, backgroundScope, policy),
        clock = clock,
        newId = { "id-${++ids}" },
    )

    private fun local(id: String, positionMs: Long, title: String = "At $positionMs") =
        AudioBookmark(id, Book, positionMs, "ch_1", title, null, Made, null)

    @Test
    fun `an add while online is sent at once and answers the server's bookmark`() = runTest {
        val bookmarks = bookmarks()

        val outcome = bookmarks.add(Book, 600_000, "ch_1", "At 600000")

        val stored = repository.stored.single()
        assertThat(outcome).isEqualTo(AddOutcome.Added(stored))
        assertThat(repository.created.single()).isEqualTo(local("id-1", 600_000))
        assertThat(bookmarks.of(Book).first()).containsExactly(stored)
        assertThat(queue().pending()).isEmpty()
    }

    @Test
    fun `an add at a second another bookmark holds reads the list again and answers that one`() = runTest {
        val there = bookmark("there", positionMs = 600_900)
        repository.stored += there
        val bookmarks = bookmarks()

        val outcome = bookmarks.add(Book, 600_100, null, "Mine")

        assertThat(outcome).isEqualTo(AddOutcome.Existing(there))
        assertThat(repository.calls).containsExactly("create 5 id-1", "list 5").inOrder()
        assertThat(bookmarks.of(Book).first()).containsExactly(there)
        assertThat(queue().pending()).isEmpty()
    }

    @Test
    fun `an add at a second taken whose list cannot be read is told as taken, not as a bookmark`() = runTest {
        repository.createResult = ApiResult.Success(BookmarkCreateResult.SameSecond)
        repository.listResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val bookmarks = bookmarks()

        val outcome = bookmarks.add(Book, 600_100, null, "Mine")

        assertThat(outcome).isEqualTo(AddOutcome.Taken(600_100))
        assertThat(bookmarks.of(Book).first()).isEmpty()
        assertThat(queue().pending()).isEmpty()
    }

    @Test
    fun `an add with a blank title is refused, and nothing is shown or queued`() = runTest {
        val bookmarks = bookmarks()

        val failure = runCatching { bookmarks.add(Book, 600_000, null, "  ") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bookmarks.of(Book).first()).isEmpty()
        assertThat(queue().pending()).isEmpty()
        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `an edit with a blank title keeps the title shown, and changes the note`() = runTest {
        val queue = queue()
        queue.record(Add(local("x", 1_000, title = "Kept")))
        val bookmarks = DefaultAudioBookmarks(queue, idleFlusher(queue), clock) { "unused" }

        bookmarks.edit(Book, "x", " ", "Typed")
        bookmarks.edit(Book, "unknown", " ", "Typed")

        assertThat(queue.pending())
            .containsExactly(Add(local("x", 1_000, title = "Kept")), Edit(Book, "x", "Kept", "Typed")).inOrder()
    }

    @Test
    fun `the same edit twice goes once, and nothing waits after it`() = runTest {
        repository.stored += local("x", 1_000)
        val bookmarks = bookmarks()
        bookmarks.refresh(Book)

        bookmarks.edit(Book, "x", "Renamed", null)
        bookmarks.edit(Book, "x", "Renamed", null)
        runCurrent()

        assertThat(repository.calls.filter { it.startsWith("edit") }).containsExactly("edit 5 x")
        assertThat(queue().pending()).isEmpty()
    }

    @Test
    fun `an add at a second already shown answers that one without a request`() = runTest {
        val there = bookmark("there", positionMs = 600_900)
        repository.stored += there
        val bookmarks = bookmarks()
        bookmarks.refresh(Book)

        val outcome = bookmarks.add(Book, 600_000, null, "Mine")

        assertThat(outcome).isEqualTo(AddOutcome.Existing(there))
        assertThat(repository.calls).containsExactly("list 5")
    }

    @Test
    fun `each add has a new id, whatever the answer`() = runTest {
        repository.stored += bookmark("there", positionMs = 600_000)
        val bookmarks = bookmarks()

        bookmarks.add(Book, 600_000, null, "Same second")
        bookmarks.add(Book, 600_000, null, "Same second again")
        bookmarks.add(Book, 900_000, null, "Elsewhere")

        assertThat(ids).isEqualTo(3)
        assertThat(repository.created.map { it.id }).containsExactly("id-1", "id-3").inOrder()
    }

    @Test
    fun `an add offline is shown at once and sent with the same client id once the network is back`() = runTest {
        repository.online = false
        val bookmarks = bookmarks()

        val outcome = bookmarks.add(Book, 600_000, "ch_1", "At 600000")

        assertThat(outcome).isEqualTo(AddOutcome.Added(local("id-1", 600_000)))
        assertThat(bookmarks.of(Book).first()).containsExactly(local("id-1", 600_000))

        repository.online = true
        advanceTimeBy(10.seconds)
        runCurrent()

        assertThat(repository.created.map { it.id }.distinct()).containsExactly("id-1")
        assertThat(repository.stored.map { it.id }).containsExactly("id-1")
        assertThat(queue().pending()).isEmpty()
    }

    @Test
    fun `edits and a delete wait in order after the add of the same bookmark`() = runTest {
        repository.online = false
        val bookmarks = bookmarks()
        bookmarks.add(Book, 600_000, null, "At 600000")
        bookmarks.edit(Book, "id-1", "Renamed", "  ")
        bookmarks.edit(Book, "id-1", "Renamed", "Typed")

        assertThat(bookmarks.of(Book).first().single().let { it.title to it.note }).isEqualTo("Renamed" to "Typed")
        bookmarks.delete(Book, "id-1")

        // The add was attempted while offline: it may be on the server, so the delete goes after it.
        assertThat(queue().pending()).containsExactly(
            Add(local("id-1", 600_000).copy(chapterId = null), attempted = true),
            Delete(Book, "id-1"),
        ).inOrder()
        assertThat(bookmarks.of(Book).first()).isEmpty()

        repository.online = true
        advanceTimeBy(10.seconds)
        runCurrent()
        assertThat(repository.calls.takeLast(2)).containsExactly("create 5 id-1", "delete 5 id-1").inOrder()
        assertThat(repository.stored).isEmpty()
    }

    @Test
    fun `an edit waits after the add, with a blank note left out`() = runTest {
        val queue = queue()
        queue.record(Add(local("x", 1_000)))
        val bookmarks = DefaultAudioBookmarks(queue, idleFlusher(queue), clock) { "unused" }

        bookmarks.edit(Book, "x", "Renamed", " ")

        assertThat(queue.pending()).containsExactly(Add(local("x", 1_000)), Edit(Book, "x", "Renamed", null))
            .inOrder()
    }

    @Test
    fun `the delete of a bookmark never sent takes it and its edits away without a request`() = runTest {
        val queue = queue()
        val bookmarks = DefaultAudioBookmarks(queue, idleFlusher(queue), clock) { "unused" }
        queue.record(Add(local("x", 1_000)))
        bookmarks.edit(Book, "x", "Renamed", null)

        bookmarks.delete(Book, "x")

        assertThat(queue.pending()).isEmpty()
        assertThat(bookmarks.of(Book).first()).isEmpty()
        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `what waits survives a new instance`() = runTest {
        repository.online = false
        bookmarks().add(Book, 600_000, null, "At 600000")

        assertThat(bookmarks(queue(dataStore)).of(Book).first().map { it.id }).containsExactly("id-1")
    }

    /** A flusher whose scope never runs: what is queued stays there for the test to see. */
    private fun idleFlusher(queue: BookmarkQueue) = BookmarkFlusher(
        queue, repository, CoroutineScope(StandardTestDispatcher()), policy,
    )
}
