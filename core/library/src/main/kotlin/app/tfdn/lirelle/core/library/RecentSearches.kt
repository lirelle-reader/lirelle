package app.tfdn.lirelle.core.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MaxRecentSearches = 10
private val RecentKey = stringPreferencesKey("recent_searches")

/**
 * The texts the user searched last, newest first. Kept on the device only, with the profile: they go with its
 * folders when the account signs out.
 */
interface RecentSearches {
    val entries: Flow<List<String>>

    suspend fun record(text: String)

    suspend fun remove(text: String)

    suspend fun clear()
}

/**
 * Moves the recent searches out of the library's preferences of an installation from before the profiles,
 * where they were kept beside how the lists are shown under the same key, into the searches of a profile. For
 * the migration.
 *
 * Copied first and removed after, as they were written: interrupted in between, it does the same again. Once
 * they are gone from the device's file nothing is touched.
 *
 * @param device the device's library preferences.
 * @param profile where the searches go; null when they are nobody's any more, and are only removed.
 */
suspend fun moveRecentSearches(device: DataStore<Preferences>, profile: DataStore<Preferences>?) {
    val searches = device.data.first()[RecentKey] ?: return
    profile?.edit { it[RecentKey] = searches }
    device.edit { it.remove(RecentKey) }
}

/** This list with [text] put first, without its earlier occurrence, cut to the limit. */
internal fun List<String>.recording(text: String, limit: Int = MaxRecentSearches): List<String> {
    val clean = text.trim()
    if (clean.isEmpty()) return this
    return (listOf(clean) + filterNot { it.equals(clean, ignoreCase = true) }).take(limit)
}

class DataStoreRecentSearches(private val dataStore: DataStore<Preferences>) : RecentSearches {

    // The searches are a convenience: a store that cannot be read or written never stops the application.
    override val entries: Flow<List<String>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { decode(it[RecentKey]) }

    override suspend fun record(text: String) = change { it.recording(text) }

    override suspend fun remove(text: String) = change { list -> list.filterNot { it == text } }

    override suspend fun clear() = change { emptyList() }

    private suspend fun change(transform: (List<String>) -> List<String>) {
        try {
            dataStore.edit { it[RecentKey] = Json.encodeToString(transform(decode(it[RecentKey]))) }
        } catch (_: IOException) {
            // Not written: the list stays as it was.
        }
    }

    private fun decode(stored: String?): List<String> = try {
        stored?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}
