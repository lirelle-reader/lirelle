package app.tfdn.lirelle.core.library

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class Presentation { Grid, List }

/**
 * What a list view remembers across launches. [sort] is the name of the view's own sort
 * enum; null means "the view's default". Filters are deliberately not stored.
 */
@Serializable
data class ViewPreferences(
    val presentation: Presentation = Presentation.Grid,
    val sort: String? = null,
    val descending: Boolean? = null,
)

interface LibraryPreferences {
    /** [key] names the view: "books", "series", "authors", "shelf". */
    fun view(key: String): Flow<ViewPreferences>

    suspend fun update(key: String, transform: (ViewPreferences) -> ViewPreferences)
}

class DataStoreLibraryPreferences(private val dataStore: DataStore<Preferences>) : LibraryPreferences {

    private val json = Json { ignoreUnknownKeys = true }

    override fun view(key: String): Flow<ViewPreferences> = dataStore.data.map { decode(it[keyOf(key)]) }

    override suspend fun update(key: String, transform: (ViewPreferences) -> ViewPreferences) {
        dataStore.edit { it[keyOf(key)] = json.encodeToString(transform(decode(it[keyOf(key)]))) }
    }

    @VisibleForTesting
    internal suspend fun corruptForTest(key: String) {
        dataStore.edit { it[keyOf(key)] = "{not json" }
    }

    private fun decode(raw: String?): ViewPreferences = try {
        raw?.let { json.decodeFromString<ViewPreferences>(it) } ?: ViewPreferences()
    } catch (_: SerializationException) {
        ViewPreferences()
    } catch (_: IllegalArgumentException) {
        ViewPreferences()
    }

    private fun keyOf(key: String) = stringPreferencesKey("view.$key")
}
