package app.tfdn.lirelle.core.reader.store

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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val Format = Json { ignoreUnknownKeys = true }

/**
 * One value kept as JSON under [name]. A store that cannot be read, or a value that no longer
 * parses, reads as [empty]; a store that cannot be written is left as it was. Changes are applied
 * one after the other, each on what the previous one left.
 */
internal class JsonValue<T>(
    private val dataStore: DataStore<Preferences>,
    name: String,
    private val serializer: KSerializer<T>,
    private val empty: T,
) {
    private val key = stringPreferencesKey(name)

    /**
     * The value, each time it changes. The store is shared with other values: a write to another
     * key neither emits nor decodes this one again.
     */
    val flow: Flow<T> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[key] }
        .distinctUntilChanged()
        .map { decode(it) }

    suspend fun read(): T = flow.first()

    suspend fun change(transform: (T) -> T) {
        try {
            dataStore.edit { it[key] = Format.encodeToString(serializer, transform(decode(it[key]))) }
        } catch (_: IOException) {
            // Not written: the value stays as it was.
        }
    }

    private fun decode(stored: String?): T = try {
        stored?.let { Format.decodeFromString(serializer, it) } ?: empty
    } catch (_: SerializationException) {
        empty
    } catch (_: IllegalArgumentException) {
        empty
    }
}
