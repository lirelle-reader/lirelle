package app.tfdn.lirelle.di

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.network.AccountPictures
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.network.di.LongTransfer
import app.tfdn.lirelle.core.network.di.LongTransferOf
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.signin.AuthByKind
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.Interceptor

/**
 * The object of the profile's own kind of server, out of those its sources bind ([SourceOf]): a kind of
 * server adds its source's module, a branch here, and its object as a parameter of each provider below.
 * Only the chosen source's object is made, and it keeps the scope its source gave it.
 */
internal fun <T> ProfileSession.chosen(
    bookOrbit: Provider<T>,
    komga: Provider<T>,
    kavita: Provider<T>,
    audiobookshelf: Provider<T>,
): T = when (kind) {
    ServerKind.BookOrbit -> bookOrbit.get()
    ServerKind.Komga -> komga.get()
    ServerKind.Kavita -> kavita.get()
    ServerKind.Audiobookshelf -> audiobookshelf.get()
}

/** Each kind's probe, in the order the kinds are asked of an address. */
internal class KindProbes(val inOrder: List<Pair<ServerKind, ServerProbe>>)

/** Each kind's sign-in. */
internal class KindSignIns(val byKind: Map<ServerKind, SourceSignIn>)

/**
 * Signing in, before any profile is open: each kind's probe, in the order the kinds are asked — BookOrbit's first, as
 * before Komga, then Komga's, then Audiobookshelf's, then Kavita's — and each kind's sign-in, under the one entry the
 * screens are given. A kind of server adds its probe and its sign-in here.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ChosenSignInModule {

    /**
     * Kavita's probe last: it guesses, from two answers another kind of server could give as well, where the three
     * others read an explicit signature of their server — BookOrbit's identity, Komga's providers with its Basic
     * challenge, Audiobookshelf's status naming its app. Asked before them, it could take their servers for Kavita's.
     */
    @Provides
    fun probes(
        @SourceOf(ServerKind.BookOrbit) bookOrbit: ServerProbe,
        @SourceOf(ServerKind.Komga) komga: ServerProbe,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: ServerProbe,
        @SourceOf(ServerKind.Kavita) kavita: ServerProbe,
    ): KindProbes = KindProbes(
        listOf(
            ServerKind.BookOrbit to bookOrbit,
            ServerKind.Komga to komga,
            ServerKind.Audiobookshelf to audiobookshelf,
            ServerKind.Kavita to kavita,
        ),
    )

    @Provides
    fun signIns(
        @SourceOf(ServerKind.BookOrbit) bookOrbit: SourceSignIn,
        @SourceOf(ServerKind.Komga) komga: SourceSignIn,
        @SourceOf(ServerKind.Kavita) kavita: SourceSignIn,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: SourceSignIn,
    ): KindSignIns = KindSignIns(
        mapOf(
            ServerKind.BookOrbit to bookOrbit,
            ServerKind.Komga to komga,
            ServerKind.Kavita to kavita,
            ServerKind.Audiobookshelf to audiobookshelf,
        ),
    )

    @Provides
    @Singleton
    fun auth(store: SessionStore, probes: KindProbes, signIns: KindSignIns): AuthRepository =
        AuthByKind(store, probes = probes.inOrder, signIns = signIns.byKind)
}

/**
 * What the open profile's server says of its books, its readings and its listenings, and what it can do, as the
 * source of its kind says it. Unscoped: each source scopes its own.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ChosenLibraryModule {

    @Provides
    fun library(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<LibraryRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<LibraryRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<LibraryRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<LibraryRepository>,
    ): LibraryRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun reader(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<ReaderRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<ReaderRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<ReaderRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<ReaderRepository>,
    ): ReaderRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun audio(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<AudioRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<AudioRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<AudioRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<AudioRepository>,
    ): AudioRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun audioBookmarks(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<AudioBookmarkRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<AudioBookmarkRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<AudioBookmarkRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<AudioBookmarkRepository>,
    ): AudioBookmarkRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun marks(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<MarksRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<MarksRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<MarksRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<MarksRepository>,
    ): MarksRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun readAlong(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<ReadAlongRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<ReadAlongRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<ReadAlongRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<ReadAlongRepository>,
    ): ReadAlongRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun history(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<HistoryRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<HistoryRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<HistoryRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<HistoryRepository>,
    ): HistoryRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun capabilities(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<SourceCapabilities>,
        @SourceOf(ServerKind.Komga) komga: Provider<SourceCapabilities>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<SourceCapabilities>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<SourceCapabilities>,
    ): SourceCapabilities = session.chosen(bookOrbit, komga, kavita, audiobookshelf)
}

/**
 * How the open profile's server is reached, as the source of its kind says: its pages, where it serves the kept
 * files and what its refusals of them are, its account, how its account is proven to it, the account's picture, and
 * its long transfers. Unscoped: each source scopes its own.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ChosenConnectionModule {

    @Provides
    fun pages(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<PageSource>,
        @SourceOf(ServerKind.Komga) komga: Provider<PageSource>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<PageSource>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<PageSource>,
    ): PageSource = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun downloadLocations(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<DownloadLocations>,
        @SourceOf(ServerKind.Komga) komga: Provider<DownloadLocations>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<DownloadLocations>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<DownloadLocations>,
    ): DownloadLocations = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun downloadRefusals(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<DownloadRefusals>,
        @SourceOf(ServerKind.Komga) komga: Provider<DownloadRefusals>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<DownloadRefusals>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<DownloadRefusals>,
    ): DownloadRefusals = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun account(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<AccountRepository>,
        @SourceOf(ServerKind.Komga) komga: Provider<AccountRepository>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<AccountRepository>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<AccountRepository>,
    ): AccountRepository = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun authorizer(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<SessionAuthorizer>,
        @SourceOf(ServerKind.Komga) komga: Provider<SessionAuthorizer>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<SessionAuthorizer>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<SessionAuthorizer>,
    ): SessionAuthorizer = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    fun accountPictures(
        session: ProfileSession,
        @SourceOf(ServerKind.BookOrbit) bookOrbit: Provider<AccountPictures>,
        @SourceOf(ServerKind.Komga) komga: Provider<AccountPictures>,
        @SourceOf(ServerKind.Kavita) kavita: Provider<AccountPictures>,
        @SourceOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<AccountPictures>,
    ): AccountPictures = session.chosen(bookOrbit, komga, kavita, audiobookshelf)

    @Provides
    @LongTransfer
    fun longTransfer(
        session: ProfileSession,
        @LongTransferOf(ServerKind.BookOrbit) bookOrbit: Provider<Interceptor>,
        @LongTransferOf(ServerKind.Komga) komga: Provider<Interceptor>,
        @LongTransferOf(ServerKind.Kavita) kavita: Provider<Interceptor>,
        @LongTransferOf(ServerKind.Audiobookshelf) audiobookshelf: Provider<Interceptor>,
    ): Interceptor = session.chosen(bookOrbit, komga, kavita, audiobookshelf)
}

/**
 * Every contract the open profile's source fulfils, as the profile's objects are given it. For the tests, which
 * check that a profile gets the implementations of its own kind of server.
 */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ProfileSourceEntry {
    fun sourceLibrary(): LibraryRepository

    fun sourceReader(): ReaderRepository

    fun sourceAudio(): AudioRepository

    fun sourceAudioBookmarks(): AudioBookmarkRepository

    fun sourceMarks(): MarksRepository

    fun sourceReadAlong(): ReadAlongRepository

    fun sourceHistory(): HistoryRepository

    fun sourcePages(): PageSource

    fun sourceDownloadLocations(): DownloadLocations

    fun sourceDownloadRefusals(): DownloadRefusals

    fun sourceAccount(): AccountRepository

    fun sourceAuthorizer(): SessionAuthorizer

    fun sourceAccountPictures(): AccountPictures

    @LongTransfer
    fun sourceLongTransfer(): Interceptor

    /** A property, not a function: the interface already has as many functions as detekt allows. */
    val sourceCapabilities: SourceCapabilities
}
