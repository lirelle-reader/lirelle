package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val SeriesPrefix = "direction.series."
private const val BookPrefix = "direction.book."

/**
 * A series and an album may have the same id: each kind has its own keys. A series's key ends with the text of
 * its id, whatever that text is: a key is any text.
 */
private fun DirectionScope.key(): Preferences.Key<String> = when (this) {
    is DirectionScope.Series -> stringPreferencesKey("$SeriesPrefix${id.value}")
    is DirectionScope.Book -> stringPreferencesKey("$BookPrefix${id.value}")
}

/**
 * Moves the reading directions out of the comics settings of an installation from before the profiles, where
 * they were kept beside the device's choices under the same keys, into the directions of a profile. For the
 * migration.
 *
 * Copied first and removed after, as they were written: interrupted in between, it does the same again. Once
 * they are gone from the device's file nothing is touched.
 *
 * @param device the device's comics settings.
 * @param profile where the directions go; null when they are nobody's any more, and are only removed.
 */
suspend fun moveReadingDirections(device: DataStore<Preferences>, profile: DataStore<Preferences>?) {
    val directions = device.data.first().asMap()
        .filterKeys { it.name.startsWith(SeriesPrefix) || it.name.startsWith(BookPrefix) }
    if (directions.isEmpty()) return
    profile?.edit { stored ->
        directions.forEach { (key, name) -> if (name is String) stored[stringPreferencesKey(key.name)] = name }
    }
    device.edit { stored -> directions.keys.forEach { stored.remove(it) } }
}

/**
 * The direction chosen for each series, and for each album that has none: the profile's half of the comics
 * settings. The series are an account's, on its server: another profile has other series under the same ids,
 * and a direction says which were read. So each profile has its own file, in its folder, and the directions go
 * with the profile.
 *
 * A direction is kept by its name: one the app does not know, or none, is no choice. A file that cannot be
 * read holds none; one that cannot be written is left as it was.
 */
internal class ReadingDirections(private val dataStore: DataStore<Preferences>) {

    /** The direction chosen for [scope], each time it changes; null when none was. */
    fun of(scope: DirectionScope): Flow<Direction?> {
        val key = scope.key()
        return dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { stored -> enumValues<Direction>().firstOrNull { it.name == stored[key] } }
            .distinctUntilChanged()
    }

    suspend fun set(scope: DirectionScope, direction: Direction) {
        try {
            dataStore.edit { it[scope.key()] = direction.name }
        } catch (_: IOException) {
            // Not written: the direction stays as it was.
        }
    }
}
