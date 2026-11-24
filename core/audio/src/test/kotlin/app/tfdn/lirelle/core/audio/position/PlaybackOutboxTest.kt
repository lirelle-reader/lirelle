package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PlaybackOutboxTest {

    @get:Rule
    val dataStores = DataStores()

    private val position = samplePlayback()
    private val later = samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000)

    private fun outbox(dataStore: DataStore<Preferences>) = DataStorePlaybackOutbox(dataStore)

    @Test
    fun `a new outbox is empty`() = runBlocking<Unit> {
        assertThat(outbox(dataStores.create()).pending()).isEmpty()
    }

    @Test
    fun `only the latest position of a book is kept`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val other = position.copy(bookId = OtherBook)

        outbox.enqueue(position)
        outbox.enqueue(other)
        outbox.enqueue(later)

        assertThat(outbox.pending()).containsExactly(other, later).inOrder()
    }

    @Test
    fun `content survives a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        outbox(dataStore).enqueue(position)

        assertThat(outbox(dataStore).pending()).containsExactly(position)
    }

    @Test
    fun `what was sent is removed, and a newer position enqueued meanwhile stays`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        outbox.enqueue(position)
        outbox.enqueue(position.copy(bookId = OtherBook))
        outbox.markSent(position.copy(bookId = OtherBook))
        assertThat(outbox.pending()).containsExactly(position)

        outbox.enqueue(later)
        outbox.markSent(position)

        assertThat(outbox.pending()).containsExactly(later)
    }

    @Test
    fun `a replacement only takes the place of the position it was made from`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())
        val moved = position.copy(assetId = "aud_two", positionMs = 1_000, operationId = "op-moved")
        outbox.enqueue(position)

        outbox.replace(position, moved)
        assertThat(outbox.pending()).containsExactly(moved)

        outbox.enqueue(later)
        outbox.replace(moved, moved.copy(positionMs = 2_000))
        assertThat(outbox.pending()).containsExactly(later)
    }

    @Test
    fun `enqueues made at the same time are all kept`() = runBlocking<Unit> {
        val outbox = outbox(dataStores.create())

        val books = (1..Writers).map { BookId("book-$it") }
        books
            .map { book -> async(Dispatchers.Default) { outbox.enqueue(position.copy(bookId = book)) } }
            .awaitAll()

        assertThat(outbox.pending().map { it.bookId }).containsExactlyElementsIn(books)
    }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("playback_outbox")] = "[1, 2" }
        val outbox = outbox(dataStore)

        assertThat(outbox.pending()).isEmpty()

        outbox.enqueue(position)
        assertThat(outbox.pending()).containsExactly(position)
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val outbox = outbox(BrokenDataStore)

        outbox.enqueue(position)
        outbox.replace(position, later)
        outbox.markSent(position)

        assertThat(outbox.pending()).isEmpty()
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("playback_outbox")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "positions":[{"bookId":"12","assetId":"aud_one","positionMs":90000,"bookTimeMs":90000,
                "at":"2026-11-15T10:00:00Z","operationId":"op-90000","baseRevision":0,
                "manifestRevision":"$SampleContent"}]}"""
        }
        val outbox = outbox(dataStore)
        // A base stored as 0 (no state known then) reads back as its text, which BookOrbit takes as none again.
        val stored = position.copy(baseVersion = "0")

        assertThat(outbox.pending()).containsExactly(stored)

        outbox.enqueue(position.copy(bookId = OtherBook))
        assertThat(outbox.pending()).containsExactly(stored, position.copy(bookId = OtherBook)).inOrder()
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `a write waiting on a revision stored as a number by the version before waits on its version`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            val key = stringPreferencesKey("playback_outbox")
            // The text the version before wrote: BookOrbit's revision as a JSON number.
            dataStore.edit {
                it[key] = """{"positions":[{"bookId":"12","assetId":"aud_one","positionMs":90000,"bookTimeMs":90000,
                    "at":"2026-11-15T10:00:00Z","operationId":"op-90000","baseRevision":7,
                    "manifestRevision":"$SampleContent"}]}"""
            }
            val outbox = outbox(dataStore)
            val waiting = position.copy(baseVersion = "7")

            assertThat(outbox.pending()).containsExactly(waiting)

            // Written again as text, and still the same write: it is marked sent as it was read.
            outbox.enqueue(position.copy(bookId = OtherBook))
            assertThat(dataStore.data.first()[key]).contains(""""baseRevision":"7"""")
            outbox.markSent(waiting)
            assertThat(outbox.pending()).containsExactly(position.copy(bookId = OtherBook))
        }

    @Test
    fun `a waiting write keeps its version, whatever its text, and none`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val over = position.copy(baseVersion = "2026-11-15T10:00:00.123Z#e3b0")
        val first = later.copy(bookId = OtherBook, baseVersion = null)
        outbox(dataStore).run {
            enqueue(over)
            enqueue(first)
        }

        assertThat(outbox(dataStore).pending()).containsExactly(over, first).inOrder()
    }

    private companion object {
        const val Writers = 20
    }
}
