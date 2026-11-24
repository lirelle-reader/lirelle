package app.tfdn.lirelle.core.downloads.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.downloads.DownloadsFiles
import app.tfdn.lirelle.core.downloads.store.DataStoreDownloadRegistry
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope

/** What the device keeps of each book of one profile. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DownloadsDataStore

/**
 * Where a profile's downloads are: the registry and the files, both in its own folders, and gone with them.
 *
 * Everything the downloads keep is one profile's for its whole life. So nothing in these stores names whose it
 * is, and nothing checks it: the account of a profile never changes.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsStoreModule {

    /** Lets go of its file when the profile closes: the folder can then be deleted, or the profile opened again. */
    @Provides
    @ProfileScoped
    @DownloadsDataStore
    fun downloadsDataStore(folders: ProfileFolders, @ProfileLife life: CoroutineScope): DataStore<Preferences> =
        folders.newDataStore(DownloadsFiles.Store, life)

    @Provides
    @ProfileScoped
    fun downloadRegistry(@DownloadsDataStore dataStore: DataStore<Preferences>): DownloadRegistry =
        DataStoreDownloadRegistry(dataStore)

    /** In what the profile keeps, never its cache area: the system does not remove it. */
    @Provides
    @ProfileScoped
    fun downloadFiles(folders: ProfileFolders): DownloadFiles =
        DownloadFiles(File(folders.files, DownloadsFiles.Folder))

    /**
     * A download, or a book's page kept beside it, may end a moment after the profile: once it has closed no
     * folder of its downloads is made again, in a profile's folder that a sign-out deletes.
     */
    @Provides
    @IntoSet
    fun makeNoDownloadFolderAfterClose(files: DownloadFiles): ProfileCloser = ProfileCloser { files.folders.close() }
}
