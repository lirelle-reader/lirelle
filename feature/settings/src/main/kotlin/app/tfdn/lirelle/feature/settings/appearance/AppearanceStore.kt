package app.tfdn.lirelle.feature.settings.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The app's theme, kept on the device: it belongs to no account and survives a sign-out. */
interface AppearanceStore {
    /** The theme chosen, each time it changes. */
    val theme: Flow<AppTheme>

    /** Writes the theme; false when it could not be written, and the store holds what it held. */
    suspend fun setTheme(theme: AppTheme): Boolean
}

private val ThemeKey = stringPreferencesKey("theme")

/**
 * The theme is kept by its name: a name the app does not know, or none, reads as the system's. A store that
 * cannot be read follows the system; one that cannot be written is left as it was.
 */
internal class DataStoreAppearanceStore(private val dataStore: DataStore<Preferences>) : AppearanceStore {

    override val theme: Flow<AppTheme> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { stored -> AppTheme.entries.firstOrNull { it.name == stored[ThemeKey] } ?: AppTheme.System }
        .distinctUntilChanged()

    override suspend fun setTheme(theme: AppTheme): Boolean =
        try {
            dataStore.edit { it[ThemeKey] = theme.name }
            true
        } catch (_: IOException) {
            // Not written: the theme stays as it was.
            false
        }
}
