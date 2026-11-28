package app.tfdn.lirelle.source.audiobookshelf.di

import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.audiobookshelf.library.AbsLibraryApi
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProfileServer
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProgress
import app.tfdn.lirelle.source.audiobookshelf.library.AbsWholeLibrary
import app.tfdn.lirelle.source.audiobookshelf.network.AbsApis
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Instant

/**
 * What the open Audiobookshelf profile holds of its server for as long as it is open, one of each for the profile:
 * its way to the server, the account's progress and the libraries and whole lists it keeps. Every list and page of
 * the profile reads them through these, so that a screen's load asks the server once for each.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object AbsLibraryStateModule {

    /** The way to the profile's server, with its token, at the address its session holds when a call is made. */
    @Provides
    @ProfileScoped
    fun server(session: ProfileSession, apis: AbsApis<AbsLibraryApi>): AbsProfileServer =
        AbsProfileServer(session, apis, AbsErrors())

    /** The account's progress, read again when what was read is older than a few seconds. */
    @Provides
    @ProfileScoped
    fun progress(server: AbsProfileServer): AbsProgress =
        AbsProgress(read = { server.remote().api.me().mediaProgress }, now = Instant::now)

    /** The libraries and the whole lists the profile keeps of its server, the libraries for a minute. */
    @Provides
    @ProfileScoped
    fun wholeLibrary(): AbsWholeLibrary = AbsWholeLibrary(now = Instant::now)
}
