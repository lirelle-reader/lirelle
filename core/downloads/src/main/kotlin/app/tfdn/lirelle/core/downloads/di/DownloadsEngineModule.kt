package app.tfdn.lirelle.core.downloads.di

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.downloads.engine.Downloader
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.network.di.LongTransfer
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import javax.inject.Qualifier
import kotlinx.coroutines.Dispatchers
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * The authenticated client, patient with a book's files ([LongTransfer]): a download that stalls for a moment
 * goes on. On connections of its own.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient

/** What works in the places of the updates, beside the kept files, and not in the kept places. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class BesideKept

/** What fetches a profile's files: with its client, into its folders. */
@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsEngineModule {

    @Provides
    @ProfileScoped
    @DownloadClient
    fun downloadClient(
        @AuthenticatedClient client: OkHttpClient,
        @LongTransfer patience: Interceptor,
    ): OkHttpClient =
        client.newBuilder()
            // On the pages' connection, a download would make every page and cover wait behind it.
            .connectionPool(ConnectionPool())
            .addInterceptor(patience)
            .build()

    /** Where each kept file is served, and what a refusal of the server is, come from the profile's source. */
    @Provides
    @ProfileScoped
    fun downloader(
        @DownloadClient client: OkHttpClient,
        files: DownloadFiles,
        locations: DownloadLocations,
        refusals: DownloadRefusals,
    ): Downloader = Downloader(client, files, locations, refusals, Dispatchers.IO)

    /** The same downloader with another target: the files of an update never go over the kept ones. */
    @Provides
    @ProfileScoped
    @BesideKept
    fun updateDownloader(
        @DownloadClient client: OkHttpClient,
        files: DownloadFiles,
        locations: DownloadLocations,
        refusals: DownloadRefusals,
    ): Downloader = Downloader(client, files.updates, locations, refusals, Dispatchers.IO)
}
