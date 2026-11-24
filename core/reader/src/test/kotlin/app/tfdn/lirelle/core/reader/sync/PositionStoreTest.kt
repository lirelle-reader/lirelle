package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PositionStoreTest {

    @get:Rule
    val dataStores = DataStores()

    private val file12 = FileId("12")
    private val file13 = FileId("13")
    private val at = Instant.parse("2026-11-14T10:00:00.123456789Z")
    private val first = LocalPosition(file12,"epubcfi(/6/4!/4/2/1:0)", 12.5, at)

    private fun store(dataStore: DataStore<Preferences>) = DataStorePositionStore(dataStore)

    @Test
    fun `a file never read has no position`() = runBlocking<Unit> {
        assertThat(store(dataStores.create()).get(file12)).isNull()
    }

    @Test
    fun `the last position of each file is kept`() = runBlocking<Unit> {
        val store = store(dataStores.create())
        val later = first.copy(cfi = null, percent = 40.0, at = at.plusSeconds(60))
        val other = LocalPosition(file13,"epubcfi(/6/8!/4/2/1:0)", 80.0, at)

        store.put(first)
        store.put(other)
        store.put(later)

        assertThat(store.get(file12)).isEqualTo(later)
        assertThat(store.get(file13)).isEqualTo(other)
    }

    @Test
    fun `positions survive a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        store(dataStore).put(first)

        assertThat(store(dataStore).get(file12)).isEqualTo(first)
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("positions")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "positions":[{"fileId":"12","cfi":"epubcfi(/6/4)","percent":3.0,"at":"2026-11-14T10:00:00Z"}]}"""
        }
        val store = store(dataStore)

        assertThat(store.get(file12))
            .isEqualTo(LocalPosition(file12,"epubcfi(/6/4)", 3.0, Instant.parse("2026-11-14T10:00:00Z")))

        store.put(first.copy(fileId = file13))
        assertThat(store.get(file12)).isNotNull()
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("positions")] = "not json" }
        val store = store(dataStore)

        assertThat(store.get(file12)).isNull()

        store.put(first)
        assertThat(store.get(file12)).isEqualTo(first)
    }

    @Test
    fun `a stored date that is not one reads as empty`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit {
            it[stringPreferencesKey("positions")] = """{"owner":{"serverUrl":"https://books.example","userId":7},
                "positions":[{"fileId":"12","percent":3.0,"at":"yesterday"}]}"""
        }

        assertThat(store(dataStore).get(file12)).isNull()
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val store = store(BrokenDataStore)

        store.put(first)

        assertThat(store.get(file12)).isNull()
    }
}
