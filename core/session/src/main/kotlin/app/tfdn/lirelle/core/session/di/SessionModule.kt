package app.tfdn.lirelle.core.session.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.session.DataStorePendingOidcStore
import app.tfdn.lirelle.core.session.DataStoreProfileRegistry
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.core.session.PendingOidcStore
import app.tfdn.lirelle.core.session.ProfileDataFormats
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.SessionAdoption
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.TokenCipher
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.session.profile.OpenProfiles
import app.tfdn.lirelle.core.session.profile.ProfileAvatars
import app.tfdn.lirelle.core.session.profile.ProfileGraphs
import app.tfdn.lirelle.core.session.profile.ProfileRoots
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import javax.inject.Qualifier
import javax.inject.Singleton

/** Long enough that two profiles of one device never draw the same; the registry draws again if they do. */
private const val ProfileIdLength = 8

private const val RegistryFile = "profiles"
private const val OldSessionFile = "session"
private const val AvatarsFolder = "avatars"

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SessionDataStore

/** The registry of profiles and what signing in needs: the device's, whoever is signed in. */
@Module
@InstallIn(SingletonComponent::class)
internal object SessionModule {

    @Provides
    @Singleton
    @SessionDataStore
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A file that cannot be parsed is replaced: the user signs in again instead of crashing at every start.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile(RegistryFile) }

    @Provides
    @Singleton
    fun tokenCipher(): TokenCipher = KeystoreTokenCipher()

    /** One registry, seen as the session of the current profile and as the profiles themselves. */
    @Provides
    @Singleton
    fun registry(@SessionDataStore dataStore: DataStore<Preferences>, cipher: TokenCipher): DataStoreProfileRegistry =
        DataStoreProfileRegistry(dataStore, cipher) { UUID.randomUUID().toString().take(ProfileIdLength) }

    @Provides
    fun sessionStore(registry: DataStoreProfileRegistry): SessionStore = registry

    @Provides
    fun profileStore(registry: DataStoreProfileRegistry): ProfileStore = registry

    @Provides
    @Singleton
    fun pendingOidcStore(@SessionDataStore dataStore: DataStore<Preferences>, cipher: TokenCipher): PendingOidcStore =
        DataStorePendingOidcStore(dataStore, cipher)

    @Provides
    @Singleton
    fun profileRoots(@ApplicationContext context: Context): ProfileRoots =
        ProfileRoots(context.filesDir, context.cacheDir)

    /** The accounts' pictures: the device's, beside the registry, so that a sleeping profile's can be shown. */
    @Provides
    @Singleton
    fun profileAvatars(@ApplicationContext context: Context): ProfileAvatars =
        FileProfileAvatars(File(context.filesDir, AvatarsFolder))

    @Provides
    fun openProfiles(graphs: ProfileGraphs): OpenProfiles = graphs

    /** For the migration of an installation from before the profiles, whose one session was in `session`. */
    @Provides
    fun sessionAdoption(@ApplicationContext context: Context, registry: DataStoreProfileRegistry): SessionAdoption =
        registry.adoption(
            sessionFile = context.preferencesDataStoreFile(OldSessionFile),
            registryFile = context.preferencesDataStoreFile(RegistryFile),
        )
}

/**
 * The registry seen as what is told of the format of a profile's data by whoever opens the profile. Apart from
 * [SessionModule], which has as many providers as one object should.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ProfileFormatsModule {

    @Provides
    fun profileDataFormats(registry: DataStoreProfileRegistry): ProfileDataFormats = registry
}
