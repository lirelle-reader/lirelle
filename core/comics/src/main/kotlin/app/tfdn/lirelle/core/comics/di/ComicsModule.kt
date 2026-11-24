package app.tfdn.lirelle.core.comics.di

import app.tfdn.lirelle.core.comics.ComicsFiles
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier

private const val StoreLimitBytes = 500L * 1024 * 1024

/** Where the pages seen by one profile are kept. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ComicsDirectory

/** What the pages seen may take on the device, in bytes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ComicsLimit

/** What the pages seen are bound by on the device, whoever is signed in. */
@Module
@InstallIn(SingletonComponent::class)
internal object ComicsDeviceModule {

    /** For each profile: the bound is not shared between them. */
    @Provides
    @ComicsLimit
    fun limit(): Long = StoreLimitBytes
}

/** The pages seen by one profile. */
@Module
@InstallIn(ProfileComponent::class)
internal object ComicStoreProfileModule {

    /**
     * In the profile's cache folder: the system may empty it when the device runs out of room, and it goes
     * with the profile, so the pages seen by one account are never there for another.
     */
    @Provides
    @ComicsDirectory
    fun directory(folders: ProfileFolders): File = File(folders.cache, ComicsFiles.Pages)

    @Provides
    @ProfileScoped
    fun store(@ComicsDirectory directory: File, @ComicsLimit limitBytes: Long): ComicStore =
        ComicStore(directory, limitBytes)

    /**
     * A reader open when the profile closes may still be fetching or drawing a page: it is not to make the
     * profile's folder again once that folder is deleted.
     */
    @Provides
    @IntoSet
    fun keepNoPageAfterClose(store: ComicStore): ProfileCloser = ProfileCloser { store.close() }
}
