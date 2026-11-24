package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.library.DefaultMarksRepository
import app.tfdn.lirelle.source.bookorbit.library.MarksApi
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** The highlights, notes and bookmarks as the open profile's server keeps them. */
@Module
@InstallIn(ProfileComponent::class)
internal object MarksModule {

    @Provides
    @ProfileScoped
    fun marksApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<MarksApi> = ApiProvider(client, json, MarksApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun marksRepository(
        api: ApiProvider<MarksApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
    ): MarksRepository = DefaultMarksRepository(
        api = api,
        errors = ErrorMapper(json),
        serverUrl = { session.session.value.serverUrl },
    )
}
