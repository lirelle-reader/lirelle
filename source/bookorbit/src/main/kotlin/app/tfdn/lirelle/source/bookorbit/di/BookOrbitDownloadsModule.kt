package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.bookorbit.downloads.BookOrbitDownloadLocations
import app.tfdn.lirelle.source.bookorbit.downloads.BookOrbitDownloadRefusals
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.serialization.json.Json

/** Where the open profile's BookOrbit server serves each kept file, and what its refusals of them are. */
@Module
@InstallIn(ProfileComponent::class)
internal object BookOrbitDownloadsModule {

    @Provides
    @SourceOf(ServerKind.BookOrbit)
    fun locations(bookOrbit: BookOrbitDownloadLocations): DownloadLocations = bookOrbit

    @Provides
    @SourceOf(ServerKind.BookOrbit)
    fun refusals(@SourceOf(ServerKind.BookOrbit) json: Json): DownloadRefusals =
        BookOrbitDownloadRefusals(ErrorMapper(json))
}
