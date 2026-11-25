package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.network.di.LongTransferOf
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.library.DefaultLibraryRepository
import app.tfdn.lirelle.source.bookorbit.library.DefaultReaderRepository
import app.tfdn.lirelle.source.bookorbit.library.LibraryApi
import app.tfdn.lirelle.source.bookorbit.library.ReaderApi
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.ServerFeatures
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * What the open profile's BookOrbit server says of its books, asked as its account: made with the profile, and
 * gone with it. And what it can do, the same for every BookOrbit server.
 *
 * Each repository asks the profile's own server, never that of "whoever is signed in": a call that ends after
 * its profile closed has asked no other server.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object BookOrbitLibraryModule {

    @Provides
    @ProfileScoped
    fun libraryApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<LibraryApi> = ApiProvider(client, json, LibraryApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun libraryRepository(
        api: ApiProvider<LibraryApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
        features: ServerFeatures,
        @SourceOf(ServerKind.BookOrbit) audio: AudioRepository,
    ): LibraryRepository =
        DefaultLibraryRepository(
            api = api,
            errors = ErrorMapper(json),
            features = features,
            serverUrl = { session.session.value.serverUrl },
            audio = audio,
        )

    @Provides
    @ProfileScoped
    fun readerApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        @LongTransferOf(ServerKind.BookOrbit) patience: Interceptor,
    ): ApiProvider<ReaderApi> =
        ApiProvider(client.newBuilder().addInterceptor(patience).build(), json, ReaderApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun readerRepository(
        api: ApiProvider<ReaderApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
    ): ReaderRepository = DefaultReaderRepository(
        api = api,
        errors = ErrorMapper(json),
        serverUrl = { session.session.value.serverUrl },
    )

    /** BookOrbit can do everything the app shows. */
    @Provides
    @SourceOf(ServerKind.BookOrbit)
    fun capabilities(): SourceCapabilities = SourceCapabilities.Everything
}
