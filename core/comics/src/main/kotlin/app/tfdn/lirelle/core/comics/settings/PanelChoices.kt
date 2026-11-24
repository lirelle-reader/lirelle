package app.tfdn.lirelle.core.comics.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.tfdn.lirelle.core.ids.BookId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val SeriesPrefix = "panels.series."
private const val BookPrefix = "panels.book."
private const val ToldPrefix = "panels.told."

/** A series and an album may have the same id: each kind has its own keys, as for the directions. */
private fun DirectionScope.panelsKey(): Preferences.Key<Boolean> = when (this) {
    is DirectionScope.Series -> booleanPreferencesKey("$SeriesPrefix${id.value}")
    is DirectionScope.Book -> booleanPreferencesKey("$BookPrefix${id.value}")
}

private fun BookId.toldKey(): Preferences.Key<Boolean> = booleanPreferencesKey("$ToldPrefix$value")

/**
 * What a profile chose of reading panel by panel: the series read so, and the albums with no series; and the
 * albums the user was already told lend themselves badly to it. Kept in the profile's file of the reading
 * directions, for the same reason: they say which series were read, and go with the profile.
 *
 * A series nothing was chosen for is not read panel by panel. A file that cannot be read holds nothing; one that
 * cannot be written is left as it was.
 */
internal class PanelChoices(private val dataStore: DataStore<Preferences>) {

    /** Whether [scope] is read panel by panel, each time it changes. */
    fun of(scope: DirectionScope): Flow<Boolean> {
        val key = scope.panelsKey()
        return stored().map { it[key] == true }.distinctUntilChanged()
    }

    suspend fun set(scope: DirectionScope, on: Boolean) = change { it[scope.panelsKey()] = on }

    /** Whether the user was already told that the album [bookId] lends itself badly to it. */
    suspend fun told(bookId: BookId): Boolean = stored().first()[bookId.toldKey()] == true

    suspend fun tell(bookId: BookId) = change { it[bookId.toldKey()] = true }

    /** What the file holds, each time it changes; nothing when it cannot be read. */
    private fun stored(): Flow<Preferences> =
        dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private suspend fun change(transform: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (_: IOException) {
            // Not written: the choice stays as it was.
        }
    }
}
