package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.network.di.PublicClient
import app.tfdn.lirelle.core.session.PendingOidcStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.BookOrbitProbe
import app.tfdn.lirelle.source.bookorbit.network.BookOrbitSignIn
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.PublicApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * What reaches a BookOrbit server before anyone is signed in: telling its servers from others, and signing in
 * to them — there is no profile yet. The app sends there what is BookOrbit's.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object BookOrbitNetworkModule {

    /** How BookOrbit's answers are read, for every API of the source; another kind reads its own its way. */
    @Provides
    @Singleton
    @SourceOf(ServerKind.BookOrbit)
    fun json(): Json = ApiJson

    @Provides
    @Singleton
    fun publicApi(
        @PublicClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<PublicApi> = ApiProvider(client, json, PublicApi::class.java)

    @Provides
    @Singleton
    @SourceOf(ServerKind.BookOrbit)
    fun probe(
        publicApi: ApiProvider<PublicApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ServerProbe = BookOrbitProbe(publicApi, ErrorMapper(json))

    @Provides
    @Singleton
    @SourceOf(ServerKind.BookOrbit)
    fun signIn(
        store: SessionStore,
        pendingOidc: PendingOidcStore,
        publicApi: ApiProvider<PublicApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        deviceLabel: DeviceLabelProvider,
    ): SourceSignIn = BookOrbitSignIn(store, pendingOidc, publicApi, ErrorMapper(json), deviceLabel)
}
