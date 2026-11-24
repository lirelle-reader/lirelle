package app.tfdn.lirelle.feature.settings.appearance.di

import android.app.UiModeManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.feature.settings.appearance.AppearanceStore
import app.tfdn.lirelle.feature.settings.appearance.DataStoreAppearanceStore
import app.tfdn.lirelle.feature.settings.appearance.NightMode
import app.tfdn.lirelle.feature.settings.appearance.SystemNightMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** How the app looks on this device, whoever is signed in: a sign-out leaves it alone. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class AppearanceDataStore

@Module
@InstallIn(SingletonComponent::class)
internal object AppearanceModule {

    /** A file of the device, outside every profile's folder: a sign-out leaves it. */
    @Provides
    @Singleton
    @AppearanceDataStore
    fun appearanceDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile("appearance") }

    @Provides
    @Singleton
    fun appearanceStore(@AppearanceDataStore dataStore: DataStore<Preferences>): AppearanceStore =
        DataStoreAppearanceStore(dataStore)

    @Provides
    fun nightMode(@ApplicationContext context: Context): NightMode =
        SystemNightMode(context.getSystemService(UiModeManager::class.java))
}
