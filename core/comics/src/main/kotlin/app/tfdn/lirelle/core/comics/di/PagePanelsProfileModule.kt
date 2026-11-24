package app.tfdn.lirelle.core.comics.di

import app.tfdn.lirelle.core.comics.ComicsFiles
import app.tfdn.lirelle.core.comics.panels.DefaultPagePanelsOpener
import app.tfdn.lirelle.core.comics.panels.PagePanelsOpener
import app.tfdn.lirelle.core.comics.panels.ZoneStore
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoSet
import java.io.File
import kotlinx.coroutines.Dispatchers

/** The zones found on the pages one profile reads panel by panel, and what finds them. */
@Module
@InstallIn(ProfileComponent::class)
internal object PagePanelsProfileModule {

    /**
     * In the profile's cache folder, apart from the pictures of the pages: the system may empty it, the zones
     * are found again, and it goes with the profile.
     */
    @Provides
    @ProfileScoped
    fun zoneStore(folders: ProfileFolders): ZoneStore = ZoneStore(File(folders.cache, ComicsFiles.Panels))

    /**
     * A reader open when the profile closes may still be cutting a page: its zones are not to make the
     * profile's folder again once that folder is deleted.
     */
    @Provides
    @IntoSet
    fun keepNoZoneAfterClose(store: ZoneStore): ProfileCloser = ProfileCloser { store.close() }

    /**
     * The cuts are computation: apart from the main thread, and from the threads that wait on files and network.
     * Two readers open at once are two cuts at once there, one per album.
     */
    @Provides
    @ProfileScoped
    fun panelsOpener(store: ZoneStore): PagePanelsOpener = DefaultPagePanelsOpener(store, Dispatchers.Default)
}
