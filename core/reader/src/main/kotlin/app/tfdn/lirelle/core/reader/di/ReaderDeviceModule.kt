package app.tfdn.lirelle.core.reader.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.reader.settings.DataStoreReaderSettingsStore
import app.tfdn.lirelle.core.reader.settings.ReaderSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** How books are shown: the device's, the same for every profile, kept when one is removed. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ReaderSettingsDataStore

/** What the reader keeps for the device, whoever reads on it. */
@Module
@InstallIn(SingletonComponent::class)
internal object ReaderDeviceModule {

    /** A file of the application, outside every profile's folder. */
    @Provides
    @Singleton
    @ReaderSettingsDataStore
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile("reader_settings") }

    @Provides
    @Singleton
    fun settingsStore(@ReaderSettingsDataStore dataStore: DataStore<Preferences>): ReaderSettingsStore =
        DataStoreReaderSettingsStore(dataStore)
}
