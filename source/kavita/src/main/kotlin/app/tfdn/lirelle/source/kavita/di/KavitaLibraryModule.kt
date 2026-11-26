package app.tfdn.lirelle.source.kavita.di

import android.content.Context
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
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.kavita.history.EmptyKavitaHistory
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaAudio
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaAudioBookmarks
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaMarks
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaReadAlong
import app.tfdn.lirelle.source.kavita.library.EmptyKavitaReader
import app.tfdn.lirelle.source.kavita.library.KavitaLibrary
import app.tfdn.lirelle.source.kavita.library.KavitaLibraryApi
import app.tfdn.lirelle.source.kavita.library.KavitaTitles
import app.tfdn.lirelle.source.kavita.library.ResourceKavitaTitles
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * What Kavita can do, the same for every Kavita server: nothing yet, but connect. Kavita has no audio at all — no
 * audiobook, no narration, no bookmark of them: those stay off for good. The rest is turned on by the pull request
 * that makes it work, as the table of the spec says (2026-11-26-049-kavita-connect-design, §6): browsing with #50,
 * reading with #51. Its Library opens on its series.
 *
 * One sort is declared, the one an empty list of books is in: the Books tab shows its sort whatever the source
 * offers, and a sort with nothing to choose from would open an empty menu.
 */
private val KavitaCapabilities = SourceCapabilities(
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
    libraryStart = LibraryStart.Series,
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
    fun reader(): ReaderRepository = EmptyKavitaReader()

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
    fun history(): HistoryRepository = EmptyKavitaHistory()

    /** Kavita's column of the table. */
    @Provides
    @SourceOf(ServerKind.Kavita)
    fun capabilities(): SourceCapabilities = KavitaCapabilities
}
