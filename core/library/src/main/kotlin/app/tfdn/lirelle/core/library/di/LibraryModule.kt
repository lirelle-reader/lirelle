package app.tfdn.lirelle.core.library.di

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.library.DataStoreLibraryPreferences
import app.tfdn.lirelle.core.library.LibraryFiles
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.MonotonicClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class LibraryDataStore

/** What the library takes from the device, whoever is signed in: how its lists are shown, and the clocks. */
@Module
@InstallIn(SingletonComponent::class)
internal object LibraryDeviceModule {

    /** The device's: how each list view is laid out, whoever is signed in. */
    @Provides
    @Singleton
    @LibraryDataStore
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    ) { context.preferencesDataStoreFile(LibraryFiles.Views) }

    @Provides
    @Singleton
    fun libraryPreferences(@LibraryDataStore dataStore: DataStore<Preferences>): LibraryPreferences =
        DataStoreLibraryPreferences(dataStore)

    @Provides
    fun monotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }

    /** The time of day, for what is shown as "2 hours ago"; never for measuring how long something took. */
    @Provides
    fun wallClock(): Clock = Clock.systemUTC()
}
