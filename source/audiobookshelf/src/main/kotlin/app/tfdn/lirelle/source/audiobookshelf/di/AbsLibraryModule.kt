package app.tfdn.lirelle.source.audiobookshelf.di

import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.audiobookshelf.history.EmptyAbsHistory
import app.tfdn.lirelle.source.audiobookshelf.library.AbsLibrary
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProfileServer
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProgress
import app.tfdn.lirelle.source.audiobookshelf.library.AbsWholeLibrary
import app.tfdn.lirelle.source.audiobookshelf.library.EmptyAbsAudio
import app.tfdn.lirelle.source.audiobookshelf.library.EmptyAbsAudioBookmarks
import app.tfdn.lirelle.source.audiobookshelf.library.EmptyAbsMarks
import app.tfdn.lirelle.source.audiobookshelf.library.EmptyAbsReadAlong
import app.tfdn.lirelle.source.audiobookshelf.library.EmptyAbsReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn

/**
 * What Audiobookshelf can do, the same for every Audiobookshelf server: nothing yet, but connect. Read-along and
 * ratings stay off for good: Audiobookshelf aligns no narration with a book's text, and rates nothing. The rest is
 * turned on by the pull request that makes it work, as the table of the spec says
 * (2026-11-27-066-audiobookshelf-connect-design, §5): browsing with #67, listening with #68, reading as #69 decides.
 * Its Library opens on its books.
 *
 * One sort is declared, the one an empty list of books is in: the Books tab shows its sort whatever the source
 * offers, and a sort with nothing to choose from would open an empty menu.
 */
private val AbsCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = false,
    authors = false,
    history = false,
    homeRows = emptySet(),
    shelfKinds = emptySet(),
    searchKinds = emptySet(),
    bookSorts = setOf(BookSort.Added),
    statusFilters = emptySet(),
    formatFilters = emptySet(),
    libraryStart = LibraryStart.Books,
)

/**
 * What the open profile's Audiobookshelf server says of its books, its readings, its listenings and its history —
 * its library from its server, nothing more until it is listened to (#68) and read (#69) — and what it can do, the
 * same for every Audiobookshelf server. Made with the profile, and gone with it.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object AbsLibraryModule {

    /** The library, from the profile's way to its server, its progress and what it keeps ([AbsLibraryStateModule]). */
    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun library(server: AbsProfileServer, progress: AbsProgress, whole: AbsWholeLibrary): LibraryRepository =
        AbsLibrary(server, progress, whole)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun reader(): ReaderRepository = EmptyAbsReader()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun audio(): AudioRepository = EmptyAbsAudio()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun audioBookmarks(): AudioBookmarkRepository = EmptyAbsAudioBookmarks()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun marks(): MarksRepository = EmptyAbsMarks()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun readAlong(): ReadAlongRepository = EmptyAbsReadAlong()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Audiobookshelf)
    fun history(): HistoryRepository = EmptyAbsHistory()

    /** Audiobookshelf's column of the table. */
    @Provides
    @SourceOf(ServerKind.Audiobookshelf)
    fun capabilities(): SourceCapabilities = AbsCapabilities
}
