package app.tfdn.lirelle.di

import android.content.Context
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.core.ui.newCoverImageLoader
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.multibindings.IntoSet
import java.io.File
import okhttp3.OkHttpClient

/**
 * The folder, in a profile's cache, of the covers it saw. An installation from before the profiles kept its
 * covers under the same name at the root of the cache: the migration moves them by it.
 */
internal const val CoversFolder = "covers"

@Module
@InstallIn(ProfileComponent::class)
internal object ImageModule {
    /**
     * One loader for the profile: covers go through the authenticated client and share one disk cache, in
     * the profile's own folder. The covers one account saw are never another's, which may not be allowed
     * those books: neither on the disk nor in memory.
     */
    @Provides
    @ProfileScoped
    fun coverImageLoader(
        @ApplicationContext context: Context,
        @AuthenticatedClient client: OkHttpClient,
        folders: ProfileFolders,
    ): ImageLoader = newCoverImageLoader(context, client, File(folders.cache, CoversFolder))

    /**
     * Lets go of the disk cache, so that its folder can be deleted and the same profile opened again. The
     * loader does not shut its disk cache down with itself, and the cache makes its folder again when it
     * writes: a cover still loading would bring back the folder of a profile that was removed.
     *
     * And empties the covers held in memory: the closed profile's objects are kept until the next profile is
     * built, for the screens still leaving, and the covers of an account that signed out would stay in
     * memory for as long as nobody signs in. The loader's own shutdown empties them today; it is said here
     * too, so that it does not rest on what a library happens to do.
     */
    @OptIn(ExperimentalCoilApi::class)
    @Provides
    @IntoSet
    fun shutCoversAtClose(loader: ImageLoader): ProfileCloser = ProfileCloser {
        loader.shutdown()
        loader.memoryCache?.clear()
        loader.diskCache?.shutdown()
    }
}

/** The app's own objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface AppProfileEntry {
    fun coverImageLoader(): ImageLoader
}

/**
 * Gives the view models of the signed-in level the app's objects of the open profile. Unscoped, and in no
 * component that outlives a profile: each view model gets those of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object AppBridges {

    @Provides
    fun covers(current: CurrentProfile): ImageLoader = current.entry<AppProfileEntry>().coverImageLoader()
}
