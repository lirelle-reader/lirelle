package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.library.AudioApi
import app.tfdn.lirelle.source.bookorbit.library.DefaultAudioRepository
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** What the open profile's server says of the books that are listened to. */
@Module
@InstallIn(ProfileComponent::class)
internal object AudioModule {

    @Provides
    @ProfileScoped
    fun audioApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<AudioApi> = ApiProvider(client, json, AudioApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun audioRepository(
        api: ApiProvider<AudioApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
    ): AudioRepository = DefaultAudioRepository(
        api = api,
        errors = ErrorMapper(json),
        serverUrl = { session.session.value.serverUrl },
        json = json,
    )
}
