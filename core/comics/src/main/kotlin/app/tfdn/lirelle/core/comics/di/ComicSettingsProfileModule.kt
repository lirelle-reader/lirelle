package app.tfdn.lirelle.core.comics.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.comics.ComicsFiles
import app.tfdn.lirelle.core.comics.settings.ComicDeviceSettings
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.comics.settings.DataStoreComicSettingsStore
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope

/** The direction each series and album of one profile is read in. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ComicDirectionsDataStore

/** The profile's half, and the whole store made of both: what reads an album is given the one of its profile. */
@Module
@InstallIn(ProfileComponent::class)
internal object ComicSettingsProfileModule {

    /** Lets go of its file when the profile closes: the folder can then be deleted, or the profile opened again. */
    @Provides
    @ProfileScoped
    @ComicDirectionsDataStore
    fun comicDirectionsDataStore(folders: ProfileFolders, @ProfileLife life: CoroutineScope): DataStore<Preferences> =
        folders.newDataStore(ComicsFiles.Directions, life)

    @Provides
    @ProfileScoped
    fun comicSettingsStore(
        device: ComicDeviceSettings,
        @ComicDirectionsDataStore directions: DataStore<Preferences>,
    ): ComicSettingsStore = DataStoreComicSettingsStore(device, directions)
}
