package app.tfdn.lirelle.core.downloads.di

import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.downloads.engine.CachedFiles
import app.tfdn.lirelle.core.downloads.offline.FileKeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.PlayerCachedAudio
import app.tfdn.lirelle.core.downloads.offline.ReaderCachedEpubs
import app.tfdn.lirelle.core.downloads.offline.ReaderCachedPages
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadMarks
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import app.tfdn.lirelle.core.downloads.offline.WaitingBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient

/** What a profile keeps with its books to open them without the server. */
@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsOfflineModule {

    /** The cover is fetched as the book's files are: signed, and patient. */
    @Provides
    @ProfileScoped
    fun keptBookData(
        files: DownloadFiles,
        @DownloadClient client: OkHttpClient,
        maps: ReadAlongMapStore,
        registry: DownloadRegistry,
    ): KeptBookData = FileKeptBookData(files, client, maps, registry)

    /** What an update keeps beside the kept copy until the swap. */
    @Provides
    @ProfileScoped
    fun waitingBookData(files: DownloadFiles, maps: ReadAlongMapStore): WaitingBookData = WaitingBookData(files, maps)
}

@Module
@InstallIn(ProfileComponent::class)
internal abstract class DownloadsOfflineBindings {

    /** What the reader's cache and the player look at before the network. */
    @Binds
    abstract fun downloadedFiles(files: RegistryDownloadedFiles): DownloadedFiles

    /** What the covers say of each book on this device. */
    @Binds
    abstract fun downloadMarks(marks: RegistryDownloadMarks): DownloadMarks

    /** A book read before it was downloaded is not fetched again. */
    @Binds
    @IntoSet
    abstract fun readerCache(epubs: ReaderCachedEpubs): CachedFiles

    /** A page read before its album was downloaded, or updated, is not fetched again. */
    @Binds
    @IntoSet
    abstract fun pageCache(pages: ReaderCachedPages): CachedFiles

    /** An audiobook's file listened to whole before it was downloaded is not fetched again. */
    @Binds
    @IntoSet
    abstract fun playerCache(audio: PlayerCachedAudio): CachedFiles
}
