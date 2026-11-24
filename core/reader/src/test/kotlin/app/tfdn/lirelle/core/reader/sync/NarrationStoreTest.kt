package app.tfdn.lirelle.core.reader.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NarrationStoreTest {

    @get:Rule
    val dataStores = DataStores()

    private val file12 = FileId("12")
    private val file13 = FileId("13")
    private val at = Instant.parse("2026-11-14T10:00:00.123456789Z")
    private val first = LocalNarration(file12, NarrationPlace(95.25, "ch1.xhtml#s3", 2), 12.5, at)

    private fun store(dataStore: DataStore<Preferences>) = DataStoreNarrationStore(dataStore)

    @Test
    fun `a file never heard has no narration`() = runBlocking<Unit> {
        assertThat(store(dataStores.create()).get(file12)).isNull()
    }

    @Test
    fun `the last narration of each file is kept`() = runBlocking<Unit> {
        val store = store(dataStores.create())
        val place = NarrationPlace(130.0, "ch2.xhtml#s1", 3)
        val later = first.copy(place = place, percent = 40.0, at = at.plusSeconds(60))
        val other = LocalNarration(file13, NarrationPlace(1.5, "a.xhtml#p1", 0), 80.0, at)

        store.put(first)
        store.put(other)
        store.put(later)

        assertThat(store.get(file12)).isEqualTo(later)
        assertThat(store.get(file13)).isEqualTo(other)
    }

    @Test
    fun `narrations survive a new instance`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        store(dataStore).put(first)

        assertThat(store(dataStore).get(file12)).isEqualTo(first)
    }

    @Test
    fun `narrations are kept apart from the text positions of the same file`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val positions = DataStorePositionStore(dataStore)
        val position = LocalPosition(file12,"epubcfi(/6/4!/4/2/1:0)", 3.0, at)
        positions.put(position)

        store(dataStore).put(first)
        positions.put(position.copy(percent = 4.0))

        assertThat(store(dataStore).get(file12)).isEqualTo(first)
        assertThat(positions.get(file12)).isEqualTo(position.copy(percent = 4.0))
    }

    @Test
    fun `a value stored with an owner field still reads, and is written again without it`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        val key = stringPreferencesKey("narrations")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},"narrations":[{"fileId":"12",""" +
                """"seconds":95.25,"fragment":"ch1.xhtml#s3","sectionIndex":2,"percent":12.5,""" +
                """"at":"2026-11-14T10:00:00.123456789Z"}]}"""
        }
        val store = store(dataStore)

        assertThat(store.get(file12)).isEqualTo(first)

        store.put(first.copy(fileId = file13))
        assertThat(store.get(file12)).isEqualTo(first)
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `a corrupt store reads as empty and can be written again`() = runBlocking<Unit> {
        val dataStore = dataStores.create()
        dataStore.edit { it[stringPreferencesKey("narrations")] = "not json" }
        val store = store(dataStore)

        assertThat(store.get(file12)).isNull()

        store.put(first)
        assertThat(store.get(file12)).isEqualTo(first)
    }

    @Test
    fun `a store that cannot be read or written never fails`() = runBlocking<Unit> {
        val store = store(BrokenDataStore)

        store.put(first)

        assertThat(store.get(file12)).isNull()
    }
}
