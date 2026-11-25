package app.tfdn.lirelle.core.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.model.ServerLibrary
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryChoiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private val novels = ServerLibrary(LibraryId("1"), "Novels")
    private val comics = ServerLibrary(LibraryId("2"), "Comics")

    /** The file of one profile's choice. */
    private fun file(profile: String = "p0") = File(folder.root, "$profile/library_choice.preferences_pb")

    private fun newDataStore(file: File = file()): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    /** Lets go of every file opened so far, as a profile that closes does: the same file can be opened again. */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `with nothing stored there is no library and none is chosen`() = runBlocking<Unit> {
        assertThat(DataStoreLibraryChoice(newDataStore()).state.first()).isEqualTo(LibraryChoiceState())
    }

    @Test
    fun `a choice survives a restart`() = runBlocking<Unit> {
        val choice = DataStoreLibraryChoice(newDataStore())
        choice.libraries(listOf(novels, comics))
        choice.choose(comics)
        closeAll()

        val reopened = DataStoreLibraryChoice(newDataStore())

        assertThat(reopened.state.first()).isEqualTo(LibraryChoiceState(listOf(novels, comics), comics))
    }

    @Test
    fun `the libraries just read are kept, in their order`() = runBlocking<Unit> {
        val choice = DataStoreLibraryChoice(newDataStore())

        choice.libraries(listOf(novels, comics))
        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(novels, comics)))

        choice.libraries(listOf(comics))
        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(comics)))
    }

    @Test
    fun `the chosen library takes the name its server gave it since`() = runBlocking<Unit> {
        val choice = DataStoreLibraryChoice(newDataStore())
        choice.libraries(listOf(novels, comics))
        choice.choose(comics)
        val renamed = comics.copy(name = "Bandes dessinées")

        choice.libraries(listOf(novels, renamed))

        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(novels, renamed), renamed))
    }

    @Test
    fun `a chosen library missing from the libraries just read is forgotten, and stays so`() = runBlocking<Unit> {
        val choice = DataStoreLibraryChoice(newDataStore())
        choice.libraries(listOf(novels, comics))
        choice.choose(comics)

        choice.libraries(listOf(novels))
        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(novels)))

        choice.libraries(listOf(novels, comics))
        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(novels, comics)))
    }

    @Test
    fun `choosing every library keeps the libraries`() = runBlocking<Unit> {
        val choice = DataStoreLibraryChoice(newDataStore())
        choice.libraries(listOf(novels, comics))
        choice.choose(novels)

        choice.choose(null)

        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(novels, comics)))
    }

    @Test
    fun `a library that is not among those kept can still be chosen`() = runBlocking<Unit> {
        val choice = DataStoreLibraryChoice(newDataStore())
        choice.libraries(listOf(novels))

        choice.choose(comics)

        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(listOf(novels), comics))
    }

    @Test
    fun `unreadable stored content reads as nothing stored, and can be written over`() = runBlocking<Unit> {
        val dataStore = newDataStore()
        dataStore.edit { it[stringPreferencesKey("library_choice")] = "{not json" }
        val choice = DataStoreLibraryChoice(dataStore)

        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState())

        choice.choose(novels)
        assertThat(choice.state.first()).isEqualTo(LibraryChoiceState(chosen = novels))
    }

    @Test
    fun `two profiles do not see each other's choice`() = runBlocking<Unit> {
        val first = DataStoreLibraryChoice(newDataStore(file("p0")))
        val second = DataStoreLibraryChoice(newDataStore(file("p1")))

        first.libraries(listOf(novels, comics))
        first.choose(comics)
        assertThat(second.state.first()).isEqualTo(LibraryChoiceState())

        second.libraries(listOf(novels))
        second.choose(novels)
        assertThat(first.state.first()).isEqualTo(LibraryChoiceState(listOf(novels, comics), comics))
        assertThat(second.state.first()).isEqualTo(LibraryChoiceState(listOf(novels), novels))
    }

    /** A store whose file cannot be read or written. */
    private val broken = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk")
    }

    @Test
    fun `a store that cannot be read has no library and none chosen`() = runBlocking<Unit> {
        assertThat(DataStoreLibraryChoice(broken).state.first()).isEqualTo(LibraryChoiceState())
    }

    @Test
    fun `a store that cannot be written is left as it is, without an error`() = runBlocking {
        val choice = DataStoreLibraryChoice(broken)

        choice.libraries(listOf(novels))
        choice.choose(novels)
    }
}
