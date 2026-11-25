package app.tfdn.lirelle.source.bookorbit.di

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
import app.tfdn.lirelle.source.bookorbit.network.AccountApi
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.BookOrbitAccountPictures
import app.tfdn.lirelle.source.bookorbit.network.DefaultAccountRepository
import app.tfdn.lirelle.source.bookorbit.network.DownloadPatience
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.PublicApi
import app.tfdn.lirelle.source.bookorbit.network.ServerFeatures
import app.tfdn.lirelle.source.bookorbit.network.TokenRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** How long one attempt of a token renewal may take. */
private const val RefreshCallTimeoutSeconds = 8L

/**
 * What speaks to the open profile's BookOrbit server as its account: its tokens and their renewal, which the
 * authenticated client of `core/network` asks for, what is asked about the account itself, where its picture is,
 * and which of its requests are long transfers.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object BookOrbitNetworkProfileModule {

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun authorizer(
        session: ProfileSession,
        @PublicClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): SessionAuthorizer {
        // Its own client: the refresher's bounded retry within the server's grace replaces OkHttp's, and each
        // attempt is short, so that every one starts while a replayed token is still answered.
        val refreshClient = client.newBuilder()
            .retryOnConnectionFailure(false)
            .callTimeout(RefreshCallTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        return TokenRefresher(session, ApiProvider(refreshClient, json, PublicApi::class.java), now = Instant::now)
    }

    @Provides
    @ProfileScoped
    fun accountApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<AccountApi> = ApiProvider(client, json, AccountApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun accountRepository(
        session: ProfileSession,
        api: ApiProvider<AccountApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): AccountRepository = DefaultAccountRepository(session, api, ErrorMapper(json))

    /** One for the profile: what its server announces is read once and shared by whoever asks. */
    @Provides
    @ProfileScoped
    fun serverFeatures(
        session: ProfileSession,
        api: ApiProvider<AccountApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ServerFeatures = ServerFeatures(api, ErrorMapper(json)) { session.session.value.serverUrl }

    /** The account's picture is a path on the server. */
    @Provides
    @SourceOf(ServerKind.BookOrbit)
    fun accountPictures(): AccountPictures = BookOrbitAccountPictures

    /** BookOrbit's file routes, told by their paths: one instance for every client that installs it. */
    @Provides
    @ProfileScoped
    @LongTransferOf(ServerKind.BookOrbit)
    fun longTransfer(): Interceptor = DownloadPatience()
}
