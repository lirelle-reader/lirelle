package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.network.di.LongTransferOf
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.library.DefaultReadAlongRepository
import app.tfdn.lirelle.source.bookorbit.library.ReadAlongApi
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * The read-along maps of the open profile's server. A map is a long transfer: the server builds it on its first
 * request, and it weighs several megabytes for a long book.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ReadAlongModule {

    @Provides
    @ProfileScoped
    fun readAlongApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        @LongTransferOf(ServerKind.BookOrbit) patience: Interceptor,
    ): ApiProvider<ReadAlongApi> =
        ApiProvider(client.newBuilder().addInterceptor(patience).build(), json, ReadAlongApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun readAlongRepository(
        api: ApiProvider<ReadAlongApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
    ): ReadAlongRepository = DefaultReadAlongRepository(
        api = api,
        errors = ErrorMapper(json),
        serverUrl = { session.session.value.serverUrl },
    )
}
