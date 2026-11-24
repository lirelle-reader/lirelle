package app.tfdn.lirelle.core.audio.bookmarks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Add
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Delete
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Edit
import app.tfdn.lirelle.core.audio.position.DataStores
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class BookmarkQueueTest {

    @get:Rule
    val dataStores = DataStores()

    private val a = bookmark("a", positionMs = 120_000)
    private val b = bookmark("b", positionMs = 600_000)

    private fun queue(dataStore: DataStore<Preferences> = dataStores.create()) =
        DataStoreBookmarkQueue(dataStore)

    private suspend fun DataStoreBookmarkQueue.shown() = book(Book).first().shown

    @Test
    fun `a new queue is empty`() = runBlocking<Unit> {
        val queue = queue()

        assertThat(queue.pending()).isEmpty()
        assertThat(queue.shown()).isEmpty()
    }

    @Test
    fun `changes wait in the order they were made, and survive a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val changes = listOf(Add(a), Edit(Book, "a", "Renamed", "Typed"), Add(b), Delete(Book, "b"))
        val queue = queue(dataStore)
        queue.record(changes[0])
        queue.record(changes[1])
        queue.record(changes[2])
        queue.stamp(Add(b))
        queue.record(changes[3])

        assertThat(queue(dataStore).pending())
            .containsExactly(changes[0], changes[1], Add(b, attempted = true), changes[3]).inOrder()
    }

    @Test
    fun `a new edit of a bookmark takes the place of the edits of it still waiting`() = runBlocking<Unit> {
        val queue = queue()
        queue.record(Add(a))
        queue.record(Edit(Book, "a", "First", null))
        queue.record(Add(b))
        queue.record(Edit(Book, "a", "Second", "Typed"))
        queue.record(Edit(Book, "a", "Second", "Typed"))

        assertThat(queue.pending()).containsExactly(Add(a), Add(b), Edit(Book, "a", "Second", "Typed")).inOrder()
    }

    @Test
    fun `the delete of a bookmark never sent takes it and its edits out of the queue`() = runBlocking<Unit> {
        val queue = queue()
        queue.record(Add(b))
        queue.record(Add(a))
        queue.record(Edit(Book, "a", "Renamed", null))

        queue.record(Delete(Book, "a"))

        assertThat(queue.pending()).containsExactly(Add(b))
        assertThat(queue.shown()).containsExactly(b)
    }

    @Test
    fun `the delete of a bookmark that may have left waits after its add, its edits dropped`() =
        runBlocking<Unit> {
            val queue = queue()
            queue.record(Add(a))
            queue.record(Edit(Book, "a", "Renamed", null))
            val stamped = queue.stamp(Add(a))

            queue.record(Delete(Book, "a"))
            queue.record(Delete(Book, "a"))

            assertThat(stamped).isEqualTo(Add(a, attempted = true))
            assertThat(queue.pending()).containsExactly(Add(a, attempted = true), Delete(Book, "a")).inOrder()
            assertThat(queue.shown()).isEmpty()
        }

    @Test
    fun `an add no longer waiting is not stamped`() = runBlocking<Unit> {
        val queue = queue()
        queue.record(Add(a))
        queue.record(Delete(Book, "a"))

        assertThat(queue.stamp(Add(a))).isNull()
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `waiting changes are laid over the server's list, in position then creation order`() = runBlocking<Unit> {
        val queue = queue()
        val sameTime = bookmark("c", positionMs = 120_000, createdAt = Made.minusSeconds(60))
        queue.keepServer(Book, listOf(a, b, bookmark("gone")), version = 0)
        queue.record(Add(sameTime))
        queue.record(Edit(Book, "b", "Renamed", "Typed"))
        queue.record(Delete(Book, "gone"))

        assertThat(queue.shown()).containsExactly(
            sameTime,
            a,
            b.copy(title = "Renamed", note = "Typed"),
        ).inOrder()
    }

    @Test
    fun `the server's answer replaces the waiting change in the list`() = runBlocking<Unit> {
        val queue = queue()
        queue.keepServer(Book, listOf(b), version = 0)
        queue.record(Add(a))
        queue.record(Delete(Book, "b"))
        val stored = a.copy(title = "Stored")

        queue.settle(Add(a), Settled.Holds(stored))
        queue.settle(Delete(Book, "b"), Settled.Gone(Book, "b"))

        assertThat(queue.pending()).isEmpty()
        assertThat(queue.book(Book).first().server).containsExactly(stored)
    }

    @Test
    fun `a list read before an answer was settled is not kept over it`() = runBlocking<Unit> {
        val queue = queue()
        val seen = queue.book(Book).first().version
        queue.record(Add(a))
        queue.settle(Add(a), Settled.Holds(a))

        queue.keepServer(Book, emptyList(), seen)

        assertThat(queue.shown()).containsExactly(a)
    }

    @Test
    fun `forgetting a bookmark takes all its changes out`() = runBlocking<Unit> {
        val queue = queue()
        queue.record(Add(a))
        queue.record(Add(b))
        queue.record(Edit(Book, "a", "Renamed", null))

        queue.forget(Book, "a")

        assertThat(queue.pending()).containsExactly(Add(b))
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("audio_bookmarks")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "books":{"5":{"bookmarks":[{"id":"b","bookId":"5","positionMs":600000,"title":"Kept"}],"version":2}},
                "changes":[{"type":"edit","bookId":"5","id":"b","title":"Renamed"}]}"""
        }
        val queue = queue(dataStore)

        assertThat(queue.pending()).containsExactly(Edit(Book, "b", "Renamed", null))
        assertThat(queue.book(Book).first().server.map { it.title }).containsExactly("Kept")
        assertThat(queue.book(Book).first().version).isEqualTo(2)

        queue.record(Add(a))
        assertThat(queue.pending()).containsExactly(Edit(Book, "b", "Renamed", null), Add(a)).inOrder()
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }
}
