package app.tfdn.lirelle.source.komga.di

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.network.AccountPictures
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.network.di.LongTransferOf
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.komga.downloads.KomgaDownloadRefusals
import app.tfdn.lirelle.source.komga.downloads.KomgaDownloadLocations
import app.tfdn.lirelle.source.komga.library.KomgaLibraryApi
import app.tfdn.lirelle.source.komga.network.KomgaAccountApi
import app.tfdn.lirelle.source.komga.network.KomgaAccountPictures
import app.tfdn.lirelle.source.komga.network.KomgaAccountRepository
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaKeyAuthorizer
import app.tfdn.lirelle.source.komga.network.PassThrough
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * What speaks to the open profile's Komga server as its account: its key, which the authenticated client of
 * `core/network` asks for, what is asked about the account itself and about its library, where its picture is
 * (nowhere), where its kept files are fetched (pages only until 3c), and which of its requests are long transfers
 * (none yet). Its comics' pages and reading are [KomgaReadingModule]'s.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object KomgaNetworkProfileModule {

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun authorizer(session: ProfileSession): SessionAuthorizer = KomgaKeyAuthorizer(session)

    @Provides
    @ProfileScoped
    fun accountApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Komga) json: Json,
    ): KomgaApis<KomgaAccountApi> = KomgaApis(client, json, KomgaAccountApi::class.java)

    @Provides
    @ProfileScoped
    fun libraryApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Komga) json: Json,
    ): KomgaApis<KomgaLibraryApi> = KomgaApis(client, json, KomgaLibraryApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun accountRepository(session: ProfileSession, api: KomgaApis<KomgaAccountApi>): AccountRepository =
        KomgaAccountRepository(session, api, KomgaErrors())

    @Provides
    @SourceOf(ServerKind.Komga)
    fun accountPictures(): AccountPictures = KomgaAccountPictures

    @Provides
    @LongTransferOf(ServerKind.Komga)
    fun longTransfer(): Interceptor = PassThrough

    /** Made for each object that takes it, as BookOrbit's: it holds nothing but the profile's pages. */
    @Provides
    @SourceOf(ServerKind.Komga)
    fun downloadLocations(@SourceOf(ServerKind.Komga) pages: PageSource): DownloadLocations =
        KomgaDownloadLocations(pages)

    @Provides
    @SourceOf(ServerKind.Komga)
    fun downloadRefusals(): DownloadRefusals = KomgaDownloadRefusals()
}
