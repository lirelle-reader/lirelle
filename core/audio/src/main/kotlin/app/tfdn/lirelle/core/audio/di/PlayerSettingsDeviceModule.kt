package app.tfdn.lirelle.core.audio.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.audio.settings.DataStorePlayerDeviceSettings
import app.tfdn.lirelle.core.audio.settings.PlayerDeviceSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The player settings: the device's, the same for every profile, kept when a profile goes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class PlayerSettingsDataStore

/**
 * The device's half of the player settings: where it lives is said here, and nowhere else. In a file of its
 * own, apart from the profile's half (`PlayerSettingsProfileModule`): no file that makes a profile's objects
 * names a folder of the application.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PlayerSettingsDeviceModule {

    /** A file of the device, outside every profile's folder: closing or removing a profile leaves it. */
    @Provides
    @Singleton
    @PlayerSettingsDataStore
    fun playerSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile(ListeningFiles.PlayerSettings) }

    /** What the settings screen shows and changes: it needs no profile for that. */
    @Provides
    @Singleton
    fun playerDeviceSettings(@PlayerSettingsDataStore dataStore: DataStore<Preferences>): PlayerDeviceSettings =
        DataStorePlayerDeviceSettings(dataStore)
}
