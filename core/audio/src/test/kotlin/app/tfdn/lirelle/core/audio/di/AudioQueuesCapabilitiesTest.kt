package app.tfdn.lirelle.core.audio.di

import app.tfdn.lirelle.core.audio.bookmarks.Book
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange
import app.tfdn.lirelle.core.audio.bookmarks.DataStoreBookmarkQueue
import app.tfdn.lirelle.core.audio.bookmarks.MemoryDataStore
import app.tfdn.lirelle.core.audio.bookmarks.bookmark
import app.tfdn.lirelle.core.audio.bookmarks.forSource
import app.tfdn.lirelle.core.audio.position.DataStoreListeningSessionQueue
import app.tfdn.lirelle.core.audio.position.DataStores
import app.tfdn.lirelle.core.audio.position.QueuedSession
import app.tfdn.lirelle.core.audio.position.forSource
import app.tfdn.lirelle.core.audio.position.sampleSession
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** The profile's listening queues follow what its source can do (spec 2c). */
class AudioQueuesCapabilitiesTest {

    @get:Rule
    val dataStores = DataStores()

    /** A source that has audiobooks, but neither reading sessions nor audio bookmarks. */
    private val audiobooksAlone = KomgaLikeCapabilities.copy(audiobooks = true)

    @Test
    fun `without reading sessions no listening session is queued`() = runBlocking<Unit> {
        val queue = AudioCoreModule.listeningSessionQueue(dataStores.create(), audiobooksAlone)

        queue.enqueue(FileId("50"), sampleSession())

        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `with reading sessions the listening session is queued`() = runBlocking<Unit> {
        val queue = AudioCoreModule.listeningSessionQueue(dataStores.create(), SourceCapabilities.Everything)

        queue.enqueue(FileId("50"), sampleSession())

        assertThat(queue.pending()).containsExactly(QueuedSession(FileId("50"), sampleSession()))
    }

    @Test
    fun `without audio bookmarks no change of them is queued nor shown`() = runBlocking<Unit> {
        val queue = AudioBookmarksModule.bookmarkQueue(MemoryDataStore(), audiobooksAlone)

        queue.record(BookmarkChange.Add(bookmark("a")))
        queue.record(BookmarkChange.Edit(Book, "a", "Renamed", null))

        assertThat(queue.pending()).isEmpty()
        assertThat(queue.book(Book).first().shown).isEmpty()
    }

    @Test
    fun `with audio bookmarks a change of them is queued`() = runBlocking<Unit> {
        val queue = AudioBookmarksModule.bookmarkQueue(MemoryDataStore(), SourceCapabilities.Everything)

        queue.record(BookmarkChange.Add(bookmark("a")))

        assertThat(queue.pending()).containsExactly(BookmarkChange.Add(bookmark("a")))
    }

    @Test
    fun `a source that can do everything gets the queues themselves`() {
        val sessions = DataStoreListeningSessionQueue(dataStores.create())
        val bookmarks = DataStoreBookmarkQueue(MemoryDataStore())

        assertThat(sessions.forSource(SourceCapabilities.Everything)).isSameInstanceAs(sessions)
        assertThat(bookmarks.forSource(SourceCapabilities.Everything)).isSameInstanceAs(bookmarks)
    }
}
