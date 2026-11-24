package app.tfdn.lirelle.core.audio.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.audio.settings.DataStorePlayerSettingsStore
import app.tfdn.lirelle.core.audio.settings.PlayerDeviceSettings
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope

/** The speed of each book of one profile. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class BookSpeedsDataStore

/** The profile's half, and the whole store made of both: what plays a book is given the one of its profile. */
@Module
@InstallIn(ProfileComponent::class)
internal object PlayerSettingsProfileModule {

    /** Lets go of its file when the profile closes: the folder can then be deleted, or the profile opened again. */
    @Provides
    @ProfileScoped
    @BookSpeedsDataStore
    fun bookSpeedsDataStore(folders: ProfileFolders, @ProfileLife life: CoroutineScope): DataStore<Preferences> =
        folders.newDataStore(ListeningFiles.BookSpeeds, life)

    @Provides
    @ProfileScoped
    fun playerSettingsStore(
        device: PlayerDeviceSettings,
        @BookSpeedsDataStore bookSpeeds: DataStore<Preferences>,
    ): PlayerSettingsStore = DataStorePlayerSettingsStore(device, bookSpeeds)
}
