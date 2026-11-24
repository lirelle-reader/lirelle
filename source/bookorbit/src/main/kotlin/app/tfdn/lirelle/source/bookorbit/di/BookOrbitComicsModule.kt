package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.comics.BookOrbitPageSource
import app.tfdn.lirelle.source.bookorbit.comics.ComicsApi
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** The pages of the comics and DjVus of the open profile's BookOrbit server, for its reader and its downloads. */
@Module
@InstallIn(ProfileComponent::class)
internal object BookOrbitComicsModule {

    @Provides
    @ProfileScoped
    fun comicsApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.BookOrbit) json: Json,
    ): ApiProvider<ComicsApi> = ApiProvider(client, json, ComicsApi::class.java)

    /**
     * The server is the profile's own, never that of "whoever is signed in": a fetch that ends after its
     * profile closed has asked no other server for a page.
     */
    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.BookOrbit)
    fun pageSource(
        api: ApiProvider<ComicsApi>,
        @SourceOf(ServerKind.BookOrbit) json: Json,
        session: ProfileSession,
    ): PageSource = BookOrbitPageSource(api, ErrorMapper(json)) { session.session.value.serverUrl }
}
