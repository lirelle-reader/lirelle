package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.library.AudioBookmarkApi
import app.tfdn.lirelle.source.bookorbit.library.DefaultAudioBookmarkRepository
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** The audio bookmarks as the open profile's server keeps them. */
@Module
@InstallIn(ProfileComponent::class)
internal object AudioBookmarkModule {

    @Provides
    @ProfileScoped
    fun audioBookmarkApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<AudioBookmarkApi> = ApiProvider(client, json, AudioBookmarkApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun audioBookmarkRepository(
        api: ApiProvider<AudioBookmarkApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
    ): AudioBookmarkRepository = DefaultAudioBookmarkRepository(
        api = api,
        errors = ErrorMapper(json),
        serverUrl = { session.session.value.serverUrl },
    )
}
