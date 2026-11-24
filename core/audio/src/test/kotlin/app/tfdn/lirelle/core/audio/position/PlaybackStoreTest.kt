package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PlaybackStoreTest {

    @get:Rule
    val dataStores = DataStores()

    private val position = samplePlayback()

    private fun store(dataStore: DataStore<Preferences>) = DataStorePlaybackStore(dataStore)

    @Test
    fun `a new store knows nothing`() = runBlocking<Unit> {
        val store = store(dataStores.create())

        assertThat(store.get(SampleBook)).isNull()
        assertThat(store.knownVersion(SampleBook)).isNull()
    }

    @Test
    fun `the latest position of each book is kept`() = runBlocking<Unit> {
        val store = store(dataStores.create())
        val later = samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000)
        val other = position.copy(bookId = OtherBook)

        store.put(position)
        store.put(other)
        store.put(later)

        assertThat(store.get(SampleBook)).isEqualTo(later)
        assertThat(store.get(OtherBook)).isEqualTo(other)
    }

    @Test
    fun `the capture time of the last write is kept, the latest only, across a new instance`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            val store = store(dataStore)

            store.setLastWritten(SampleBook, position.at.plusSeconds(10))
            store.setLastWritten(SampleBook, position.at)
            store.setLastWritten(OtherBook, position.at)

            assertThat(store(dataStore).lastWritten(SampleBook)).isEqualTo(position.at.plusSeconds(10))
            assertThat(store(dataStore).lastWritten(OtherBook)).isEqualTo(position.at)
            assertThat(store(dataStore).lastWritten(BookId("14"))).isNull()
        }

    @Test
    fun `the version known for each book is kept`() = runBlocking<Unit> {
        val store = store(dataStores.create())

        store.setKnownVersion(SampleBook, "v3")
        store.setKnownVersion(OtherBook, "v1")
        store.setKnownVersion(SampleBook, "e3b0c442")

        assertThat(store.knownVersion(SampleBook)).isEqualTo("e3b0c442")
        assertThat(store.knownVersion(OtherBook)).isEqualTo("v1")
    }

    @Test
    fun `positions and versions survive a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val waiting = position.copy(baseVersion = "v3")
        store(dataStore).run {
            put(waiting)
            setKnownVersion(SampleBook, "v3")
        }

        val reopened = store(dataStore)

        assertThat(reopened.get(SampleBook)).isEqualTo(waiting)
        assertThat(reopened.knownVersion(SampleBook)).isEqualTo("v3")
    }

    @Test
    fun `a known revision and a base stored as numbers by the version before read back as their versions`() =
        runBlocking<Unit> {
            val dataStore = dataStores.create()
            val key = stringPreferencesKey("playback")
            // The text the version before wrote: BookOrbit's revisions as JSON numbers.
            dataStore.edit {
                it[key] = """{"positions":[{"bookId":"12","assetId":"aud_one","positionMs":90000,"bookTimeMs":90000,
                    "at":"2026-11-15T10:00:00Z","operationId":"op-90000","baseRevision":3,
                    "manifestRevision":"$SampleContent"}],"revisions":{"12":3,"13":0}}"""
            }
            val store = store(dataStore)

            assertThat(store.get(SampleBook)).isEqualTo(position.copy(baseVersion = "3"))
            assertThat(store.knownVersion(SampleBook)).isEqualTo("3")
            assertThat(store.knownVersion(OtherBook)).isEqualTo("0")

            // Written again: the versions as text, the number's text unchanged.
            store.setKnownVersion(BookId("14"), "v1")
            assertThat(store(dataStore).knownVersion(SampleBook)).isEqualTo("3")
            assertThat(store(dataStore).get(SampleBook)).isEqualTo(position.copy(baseVersion = "3"))
            assertThat(dataStore.data.first()[key]).contains(""""12":"3"""")
        }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("playback")] = "{\"positions\": 3" }
        val store = store(dataStore)

        assertThat(store.get(SampleBook)).isNull()

        store.put(position)
        assertThat(store.get(SampleBook)).isEqualTo(position)
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val store = store(BrokenDataStore)

        store.put(position)
        store.setKnownVersion(SampleBook, "v3")

        assertThat(store.get(SampleBook)).isNull()
        assertThat(store.knownVersion(SampleBook)).isNull()
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("playback")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "positions":[{"bookId":"12","assetId":"aud_one","positionMs":90000,"bookTimeMs":90000,
                "at":"2026-11-15T10:00:00Z","operationId":"op-90000","baseRevision":0,
                "manifestRevision":"$SampleContent"}],
                "revisions":{"12":3},"written":{"12":"2026-11-15T10:00:00Z"},
                "places":{"12":[{"assetId":"aud_one","positionMs":90000}]}}"""
        }
        val store = store(dataStore)
        // A base stored as 0 (no state known then) reads back as its text, which BookOrbit takes as none again.
        val stored = position.copy(baseVersion = "0")

        assertThat(store.get(SampleBook)).isEqualTo(stored)
        assertThat(store.knownVersion(SampleBook)).isEqualTo("3")
        assertThat(store.lastWritten(SampleBook)).isEqualTo(Instant.parse("2026-11-15T10:00:00Z"))
        assertThat(store.writtenPlaces(SampleBook)).containsExactly(WrittenPlace("aud_one", 90_000))

        store.setKnownVersion(OtherBook, "v1")
        assertThat(store.get(SampleBook)).isEqualTo(stored)
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }
}
