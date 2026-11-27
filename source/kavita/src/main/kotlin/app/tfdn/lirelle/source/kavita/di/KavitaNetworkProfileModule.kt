package app.tfdn.lirelle.source.kavita.di

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
import app.tfdn.lirelle.source.kavita.comics.NoKavitaPages
import app.tfdn.lirelle.source.kavita.downloads.KavitaDownloadRefusals
import app.tfdn.lirelle.source.kavita.downloads.NoKavitaDownloadLocations
import app.tfdn.lirelle.source.kavita.library.KavitaLibraryApi
import app.tfdn.lirelle.source.kavita.network.KavitaAccountApi
import app.tfdn.lirelle.source.kavita.network.KavitaAccountPictures
import app.tfdn.lirelle.source.kavita.network.KavitaAccountRepository
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.KavitaKeyAuthorizer
import app.tfdn.lirelle.source.kavita.network.PassThrough
import app.tfdn.lirelle.source.kavita.reading.KavitaReadingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * What speaks to the open profile's Kavita server as its account: its key, which the authenticated client of
 * `core/network` asks for, what is asked about the account itself and of its library, where its picture is
 * (nowhere), its pages and kept files (none until #51), and which of its requests are long transfers (none yet).
 */
@Module
@InstallIn(ProfileComponent::class)
internal object KavitaNetworkProfileModule {

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun authorizer(session: ProfileSession): SessionAuthorizer = KavitaKeyAuthorizer(session)

    @Provides
    @ProfileScoped
    fun accountApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Kavita) json: Json,
    ): KavitaApis<KavitaAccountApi> = KavitaApis(client, json, KavitaAccountApi::class.java)

    /** The library is read with the profile's client: a key the server refuses marks the profile there. */
    @Provides
    @ProfileScoped
    fun libraryApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Kavita) json: Json,
    ): KavitaApis<KavitaLibraryApi> = KavitaApis(client, json, KavitaLibraryApi::class.java)

    /** What reading asks (progress, pages, files, history) goes through the profile's client too. */
    @Provides
    @ProfileScoped
    fun readingApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Kavita) json: Json,
    ): KavitaApis<KavitaReadingApi> = KavitaApis(client, json, KavitaReadingApi::class.java)

    /** The account is read with the profile's client: a key the server refuses marks the profile there. */
    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun accountRepository(session: ProfileSession, api: KavitaApis<KavitaAccountApi>): AccountRepository =
        KavitaAccountRepository(session, api, KavitaErrors())

    @Provides
    @SourceOf(ServerKind.Kavita)
    fun accountPictures(): AccountPictures = KavitaAccountPictures

    @Provides
    @LongTransferOf(ServerKind.Kavita)
    fun longTransfer(): Interceptor = PassThrough

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun pages(): PageSource = NoKavitaPages()

    /** Made for each object that takes it, as the other sources': it holds nothing. */
    @Provides
    @SourceOf(ServerKind.Kavita)
    fun downloadLocations(): DownloadLocations = NoKavitaDownloadLocations()

    @Provides
    @SourceOf(ServerKind.Kavita)
    fun downloadRefusals(): DownloadRefusals = KavitaDownloadRefusals()
}
