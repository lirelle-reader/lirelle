package app.tfdn.lirelle.source.kavita.di

import android.content.Context
import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
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
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.kavita.history.KavitaHistory
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaAudio
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaAudioBookmarks
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaMarks
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaReadAlong
import app.tfdn.lirelle.source.kavita.library.KavitaLibrary
import app.tfdn.lirelle.source.kavita.library.KavitaLibraryApi
import app.tfdn.lirelle.source.kavita.library.KavitaTitles
import app.tfdn.lirelle.source.kavita.library.ResourceKavitaTitles
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.reading.KavitaReader
import app.tfdn.lirelle.source.kavita.reading.KavitaReadingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * What Kavita can do, the same for every Kavita server (spec 2026-11-26-050-kavita-browse-design, §9 and §14).
 *
 * #50 turns browsing on: Home's rows, the Library's series, authors and shelves (the reading lists, as
 * collections), the genres, Search, and books sorted and filtered as far as Kavita's filters go. The Library opens
 * on its series.
 *
 * There is no Books tab: Kavita cannot list every book of the library, only its series, so
 * [SourceCapabilities.libraryBooks] is off and Home's "See all" opens the row's own screen. A series in a list comes
 * without its numbers of books and of books read, so [SourceCapabilities.seriesCounts] is off too.
 *
 * Ratings stay off: Kavita rates a series per user, not a book. #51 turns on the history and the annotations with
 * reading. Kavita has no audio at all — no audiobook, no narration, no bookmark of them — and no reading sessions:
 * those stay off for good.
 */
private val KavitaCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = true,
    authors = true,
    history = false,
    homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.WantToRead, HomeRowKind.RecentlyAdded),
    shelfKinds = setOf(ShelfKind.Collection),
    searchKinds = setOf(SearchKind.Books, SearchKind.Series, SearchKind.Authors),
    bookSorts = setOf(BookSort.Added, BookSort.Title),
    statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
    formatFilters = setOf(FormatFilter.Epub, FormatFilter.Pdf, FormatFilter.Comics),
    libraryStart = LibraryStart.Series,
    libraryBooks = false,
    seriesCounts = false,
)

/**
 * What the open profile's Kavita server says of its books, its readings and its history — its library as far as
 * #50 brings it, nothing to read until #51 — and what it can do, the same for every Kavita server. Made with the
 * profile, and gone with it.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object KavitaLibraryModule {

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun library(
        session: ProfileSession,
        api: KavitaApis<KavitaLibraryApi>,
        titles: KavitaTitles,
    ): LibraryRepository = KavitaLibrary(session, api, titles, KavitaErrors())

    /** The titles of the books Kavita knows no title of, in the language the app is shown in. */
    @Provides
    fun titles(@ApplicationContext context: Context): KavitaTitles = ResourceKavitaTitles(context)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun reader(
        session: ProfileSession,
        reading: KavitaApis<KavitaReadingApi>,
        library: KavitaApis<KavitaLibraryApi>,
        titles: KavitaTitles,
    ): ReaderRepository = KavitaReader(session, reading, library, titles, KavitaErrors())

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun audio(): AudioRepository = EmptyKavitaAudio()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun audioBookmarks(): AudioBookmarkRepository = EmptyKavitaAudioBookmarks()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun marks(): MarksRepository = EmptyKavitaMarks()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun readAlong(): ReadAlongRepository = EmptyKavitaReadAlong()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Kavita)
    fun history(
        session: ProfileSession,
        reading: KavitaApis<KavitaReadingApi>,
        library: KavitaApis<KavitaLibraryApi>,
        titles: KavitaTitles,
        cache: ReadRecordCache,
    ): HistoryRepository = KavitaHistory(session, reading, library, titles, KavitaErrors(), cache)

    /** Kavita's column of the table. */
    @Provides
    @SourceOf(ServerKind.Kavita)
    fun capabilities(): SourceCapabilities = KavitaCapabilities
}
