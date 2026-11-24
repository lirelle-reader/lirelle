package app.tfdn.lirelle.core.reader.di

import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.reader.ReadingFiles
import app.tfdn.lirelle.core.reader.cache.DefaultReaderCache
import app.tfdn.lirelle.core.reader.cache.ReaderCache
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

private const val CacheLimitBytes = 500L * 1024 * 1024

/** The books a profile opened, kept for the next time: its own, since they look at its downloads first. */
@Module
@InstallIn(ProfileComponent::class)
internal object ReaderCacheModule {

    /**
     * In the profile's cache area: the system may empty it when the device runs out of room, and it goes with
     * the profile. The books downloaded, which the downloads bind, are looked at first.
     */
    @Provides
    @ProfileScoped
    fun readerCache(
        folders: ProfileFolders,
        repository: ReaderRepository,
        downloaded: DownloadedFiles,
        holds: BookHolds,
    ): DefaultReaderCache = DefaultReaderCache(
        repository = repository,
        directory = File(folders.cache, ReadingFiles.Cache),
        limitBytes = CacheLimitBytes,
        io = Dispatchers.IO,
        downloaded = downloaded,
        books = holds,
    )

    @Provides
    fun readerCacheOfProfile(cache: DefaultReaderCache): ReaderCache = cache

    /**
     * A reader outlives its profile by a moment, and the profile's cache folder is deleted at a sign-out: once
     * the profile has closed, a book still being fetched is not kept, and the folder is not made again.
     */
    @Provides
    @IntoSet
    fun keepNoBookAfterClose(cache: DefaultReaderCache): ProfileCloser = ProfileCloser { cache.close() }
}
