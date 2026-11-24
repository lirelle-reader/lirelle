package app.tfdn.lirelle.source.komga.di

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.komga.comics.KomgaPageSource
import app.tfdn.lirelle.source.komga.comics.KomgaPagesApi
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.reading.KomgaReadingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * What the open profile asks its Komga server to read its comics: their pages, and their places (the reader itself
 * is [KomgaLibraryModule]'s, beside the other repositories), with the profile's key.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object KomgaReadingModule {

    @Provides
    @ProfileScoped
    fun pagesApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Komga) json: Json,
    ): KomgaApis<KomgaPagesApi> = KomgaApis(client, json, KomgaPagesApi::class.java)

    /**
     * The server is the profile's own, never that of "whoever is signed in": a fetch that ends after its profile
     * closed has asked no other server for a page. One for the profile: the reading writes places with the formats
     * of the pages it keeps.
     */
    @Provides
    @ProfileScoped
    fun komgaPages(session: ProfileSession, api: KomgaApis<KomgaPagesApi>): KomgaPageSource =
        KomgaPageSource(api, KomgaErrors()) { session.session.value.serverUrl }

    @Provides
    @SourceOf(ServerKind.Komga)
    fun pages(pages: KomgaPageSource): PageSource = pages

    @Provides
    @ProfileScoped
    fun readingApi(
        @AuthenticatedClient client: OkHttpClient,
        @SourceOf(ServerKind.Komga) json: Json,
    ): KomgaApis<KomgaReadingApi> = KomgaApis(client, json, KomgaReadingApi::class.java)
}
