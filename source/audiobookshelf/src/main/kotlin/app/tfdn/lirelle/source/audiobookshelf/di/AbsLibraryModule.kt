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
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.StatusFilter
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
 * What Audiobookshelf can do, the same for every Audiobookshelf server (spec 2026-11-28-067, §9): it can be browsed.
 * Genres, authors, Home's Continue, Up next and Recently added, its collections and playlists, Search's books, series
 * and authors, the Books list's sorts (Audiobookshelf sorts by series only within one) and its filters of status and
 * format. Its Library opens on its books and always shows one library, as Audiobookshelf lists nothing across its
 * libraries: its selector offers no "All".
 *
 * Read-along and ratings stay off for good: Audiobookshelf aligns no narration with a book's text, and rates nothing.
 * Listening (#68) turns on audiobooks, their bookmarks, the reading sessions and the History; reading (#69) decides
 * the annotations.
 */
private val AbsCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = true,
    authors = true,
    history = false,
    homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded),
    shelfKinds = setOf(ShelfKind.Collection, ShelfKind.Playlist),
    searchKinds = setOf(SearchKind.Books, SearchKind.Series, SearchKind.Authors),
    bookSorts = setOf(BookSort.Added, BookSort.Title, BookSort.Author, BookSort.Published),
    statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
    formatFilters = setOf(FormatFilter.Audio, FormatFilter.Epub, FormatFilter.Pdf, FormatFilter.Comics),
    libraryStart = LibraryStart.Books,
    libraryAlwaysChosen = true,
)

/**
 * What the open profile's Audiobookshelf server says of its books, its readings, its listenings and its history —
 * its library from its server, nothing more until it is listened to (#68) and read (#69): the reader, the audio,
 * the bookmarks, the marks, the read-along and the History are empty until then — and what it can do, the same for
 * every Audiobookshelf server. Made with the profile, and gone with it.
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
