package app.tfdn.lirelle.core.downloads.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.downloads.store.DataStoreDownloadSettingsStore
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Which networks downloads may use: the device's, the same for every profile and kept when one is removed. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DownloadSettingsDataStore

/** What the downloads keep for the device, whoever is signed in: their settings. */
@Module
@InstallIn(SingletonComponent::class)
internal object DownloadsDeviceModule {

    /** A file of its own, outside every profile's folders. */
    @Provides
    @Singleton
    @DownloadSettingsDataStore
    fun downloadSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile("download_settings") }

    @Provides
    @Singleton
    fun downloadSettingsStore(@DownloadSettingsDataStore dataStore: DataStore<Preferences>): DownloadSettingsStore =
        DataStoreDownloadSettingsStore(dataStore)
}
