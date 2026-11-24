package app.tfdn.lirelle.core.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentSearchesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "library.preferences_pb") }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the newest text comes first`() {
        assertThat(listOf("a", "b").recording("c")).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `a text already there moves to the top, whatever its case or spaces`() {
        assertThat(listOf("Mars", "king").recording("  mars ")).containsExactly("mars", "king").inOrder()
    }

    @Test
    fun `only ten are kept`() {
        val full = (1..10).map { "t$it" }

        assertThat(full.recording("new")).hasSize(10)
        assertThat(full.recording("new")).doesNotContain("t10")
    }

    @Test
    fun `a blank text is not recorded`() {
        assertThat(listOf("a").recording("   ")).containsExactly("a")
    }

    @Test
    fun `the store records, removes and clears`() = runBlocking {
        val store = DataStoreRecentSearches(newDataStore())

        store.record("mars")
        store.record("king")
        assertThat(store.entries.first()).containsExactly("king", "mars").inOrder()

        store.remove("king")
        assertThat(store.entries.first()).containsExactly("mars")

        store.clear()
        assertThat(store.entries.first()).isEmpty()
    }

    /** A store whose file cannot be read or written. */
    private val broken = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk")
    }

    @Test
    fun `a store that cannot be read has no search`() = runBlocking<Unit> {
        assertThat(DataStoreRecentSearches(broken).entries.first()).isEmpty()
    }

    @Test
    fun `a store that cannot be written is left as it is, without an error`() = runBlocking {
        val store = DataStoreRecentSearches(broken)

        store.record("mars")
        store.remove("mars")
        store.clear()
    }

    @Test
    fun `an unreadable value reads as no search`() = runBlocking<Unit> {
        val dataStore = newDataStore()
        dataStore.edit { it[stringPreferencesKey("recent_searches")] = "not json" }
        val store = DataStoreRecentSearches(dataStore)

        assertThat(store.entries.first()).isEmpty()

        store.record("mars")
        assertThat(store.entries.first()).containsExactly("mars")
    }
}
