package app.tfdn.lirelle.core.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.model.ServerLibrary
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val ChoiceKey = stringPreferencesKey("library_choice")

/** What the Library destination shows of its server: [libraries] as last read, and the one [chosen], null for all. */
data class LibraryChoiceState(val libraries: List<ServerLibrary> = emptyList(), val chosen: ServerLibrary? = null)

/** The open profile's own choice of a library, kept on the device with the last list of its server's libraries. */
interface LibraryChoice {
    val state: Flow<LibraryChoiceState>

    /**
     * Narrows the Library destination to [library]; null shows every library. A library that is not among
     * those kept is chosen all the same: the list may be older than what the user was shown.
     */
    suspend fun choose(library: ServerLibrary?)

    /** Keeps [libraries] as the server's, just read; a chosen library that is not among them is forgotten. */
    suspend fun libraries(libraries: List<ServerLibrary>)
}

/** A library as it is written: its id's text and its name. */
@Serializable
private data class StoredLibrary(val id: LibraryId, val name: String) {
    constructor(library: ServerLibrary) : this(library.id, library.name)

    fun library() = ServerLibrary(id, name)
}

/** The whole choice as it is written, in one value: the list and the chosen library never disagree. */
@Serializable
private data class StoredChoice(val libraries: List<StoredLibrary> = emptyList(), val chosen: StoredLibrary? = null) {
    constructor(state: LibraryChoiceState) : this(
        libraries = state.libraries.map(::StoredLibrary),
        chosen = state.chosen?.let(::StoredLibrary),
    )

    fun state() = LibraryChoiceState(libraries.map { it.library() }, chosen?.library())
}

/** This state once [libraries] were read from the server: the chosen one as it is among them, or none. */
internal fun LibraryChoiceState.reading(libraries: List<ServerLibrary>): LibraryChoiceState =
    LibraryChoiceState(libraries, libraries.firstOrNull { it.id == chosen?.id })

class DataStoreLibraryChoice(private val dataStore: DataStore<Preferences>) : LibraryChoice {

    private val json = Json { ignoreUnknownKeys = true }

    // The choice is a convenience: a store that cannot be read or written never stops the application.
    override val state: Flow<LibraryChoiceState> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { decode(it[ChoiceKey]) }

    override suspend fun choose(library: ServerLibrary?) = change { it.copy(chosen = library) }

    override suspend fun libraries(libraries: List<ServerLibrary>) = change { it.reading(libraries) }

    private suspend fun change(transform: (LibraryChoiceState) -> LibraryChoiceState) {
        try {
            dataStore.edit { it[ChoiceKey] = json.encodeToString(StoredChoice(transform(decode(it[ChoiceKey])))) }
        } catch (_: IOException) {
            // Not written: the choice stays as it was.
        }
    }

    private fun decode(stored: String?): LibraryChoiceState = try {
        stored?.let { json.decodeFromString<StoredChoice>(it).state() } ?: LibraryChoiceState()
    } catch (_: SerializationException) {
        LibraryChoiceState()
    } catch (_: IllegalArgumentException) {
        LibraryChoiceState()
    }
}
