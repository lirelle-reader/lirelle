package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.history.BookOrbitHistoryRepository
import app.tfdn.lirelle.source.bookorbit.history.HistoryApi
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** The reading history as the open profile's BookOrbit server keeps it. */
@Module
@InstallIn(ProfileComponent::class)
internal object BookOrbitHistoryModule {

    @Provides
    @ProfileScoped
    fun historyApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<HistoryApi> = ApiProvider(client, json, HistoryApi::class.java)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun historyRepository(
        api: ApiProvider<HistoryApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
        cache: ReadRecordCache,
    ): HistoryRepository = BookOrbitHistoryRepository(
        api = api,
        errors = ErrorMapper(json),
        serverUrl = { session.session.value.serverUrl },
        cache = cache,
    )
}
