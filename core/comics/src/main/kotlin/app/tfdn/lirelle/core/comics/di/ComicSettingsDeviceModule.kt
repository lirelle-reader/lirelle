package app.tfdn.lirelle.core.comics.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.comics.ComicsFiles
import app.tfdn.lirelle.core.comics.settings.ComicDeviceSettings
import app.tfdn.lirelle.core.comics.settings.DataStoreComicDeviceSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** How comics are shown: the device's, the same for every profile, kept when a profile goes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ComicSettingsDataStore

/**
 * The device's half of the comics settings: where it lives is said here, and nowhere else. In a file of its
 * own, apart from the profile's half (`ComicSettingsProfileModule`): no file that makes a profile's objects
 * names a folder of the application.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ComicSettingsDeviceModule {

    /** A file of the device, outside every profile's folder: closing or removing a profile leaves it. */
    @Provides
    @Singleton
    @ComicSettingsDataStore
    fun comicSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile(ComicsFiles.Settings) }

    /** What the settings screen shows and changes: it needs no profile for that. */
    @Provides
    @Singleton
    fun comicDeviceSettings(@ComicSettingsDataStore dataStore: DataStore<Preferences>): ComicDeviceSettings =
        DataStoreComicDeviceSettings(dataStore)
}
