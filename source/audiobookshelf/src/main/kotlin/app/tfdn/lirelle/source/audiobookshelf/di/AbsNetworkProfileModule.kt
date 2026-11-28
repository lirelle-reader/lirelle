package app.tfdn.lirelle.source.audiobookshelf.di

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.network.AccountPictures
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.network.di.LongTransferOf
import app.tfdn.lirelle.core.network.di.PublicClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.audiobookshelf.comics.NoAbsPages
import app.tfdn.lirelle.source.audiobookshelf.downloads.AbsDownloadRefusals
import app.tfdn.lirelle.source.audiobookshelf.downloads.NoAbsDownloadLocations
import app.tfdn.lirelle.source.audiobookshelf.library.AbsLibraryApi
import app.tfdn.lirelle.source.audiobookshelf.network.AbsAccountApi
import app.tfdn.lirelle.source.audiobookshelf.network.AbsAccountPictures
import app.tfdn.lirelle.source.audiobookshelf.network.AbsAccountRepository
import app.tfdn.lirelle.source.audiobookshelf.network.AbsApis
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import app.tfdn.lirelle.source.audiobookshelf.network.AbsJson
import app.tfdn.lirelle.source.audiobookshelf.network.AbsPassThrough
import app.tfdn.lirelle.source.audiobookshelf.network.AbsTokenRefresher
import app.tfdn.lirelle.source.audiobookshelf.network.absRefreshApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Instant
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * What speaks to the open profile's Audiobookshelf server as its account: its tokens and their renewal, which the
 * authenticated client of `core/network` asks for, what is asked about the account itself, where its picture is
 * (nowhere), its pages and kept files (none until #68 and #69), and which of its requests are long transfers (none
 * yet).
 */
@Module
@InstallIn(ProfileComponent::class)
internal object AbsNetworkProfileModule {

    /** The renewal has its own client, made from the public one ([absRefreshApi]): its retry is the authorizer's. */
    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun authorizer(session: ProfileSession, @PublicClient client: OkHttpClient): SessionAuthorizer =
        AbsTokenRefresher(session, absRefreshApi(client), Instant::now)

    @Provides
    @ProfileScoped
    fun accountApi(@AuthenticatedClient client: OkHttpClient): AbsApis<AbsAccountApi> =
        AbsApis(client, AbsJson, AbsAccountApi::class.java)

    /** The library's routes, on the profile's client: its token goes with each call, and is renewed. */
    @Provides
    @ProfileScoped
    fun libraryApi(@AuthenticatedClient client: OkHttpClient): AbsApis<AbsLibraryApi> =
        AbsApis(client, AbsJson, AbsLibraryApi::class.java)

    /** The account is read with the profile's client: a session the server refuses to renew marks the profile there. */
    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun accountRepository(session: ProfileSession, api: AbsApis<AbsAccountApi>): AccountRepository =
        AbsAccountRepository(session, api, AbsErrors())

    @Provides
    @SourceOf(ServerKind.Audiobookshelf)
    fun accountPictures(): AccountPictures = AbsAccountPictures

    @Provides
    @LongTransferOf(ServerKind.Audiobookshelf)
    fun longTransfer(): Interceptor = AbsPassThrough

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun pages(): PageSource = NoAbsPages()

    /** Made for each object that takes it, as the other sources': it holds nothing. */
    @Provides
    @SourceOf(ServerKind.Audiobookshelf)
    fun downloadLocations(): DownloadLocations = NoAbsDownloadLocations()

    @Provides
    @SourceOf(ServerKind.Audiobookshelf)
    fun downloadRefusals(): DownloadRefusals = AbsDownloadRefusals()
}
