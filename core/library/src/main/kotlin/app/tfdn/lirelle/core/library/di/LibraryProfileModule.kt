package app.tfdn.lirelle.core.library.di

import app.tfdn.lirelle.core.library.DataStoreLibraryChoice
import app.tfdn.lirelle.core.library.DataStoreRecentSearches
import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryFiles
import app.tfdn.lirelle.core.library.RecentSearches
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope

/**
 * What the library keeps for one profile on the device. Made with the profile, in its folders, and gone with
 * them. What its server says of its books comes from the profile's source.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object LibraryProfileModule {

    /** A file of its own: what was searched is the account's, where the views' layout is the device's. */
    @Provides
    @ProfileScoped
    fun recentSearches(folders: ProfileFolders, @ProfileLife life: CoroutineScope): RecentSearches =
        DataStoreRecentSearches(folders.newDataStore(LibraryFiles.Searches, life))

    /** A file of its own too: the libraries are the server's, and the one shown is the account's choice. */
    @Provides
    @ProfileScoped
    fun libraryChoice(folders: ProfileFolders, @ProfileLife life: CoroutineScope): LibraryChoice =
        DataStoreLibraryChoice(folders.newDataStore(LibraryFiles.Choice, life))
}
