package app.tfdn.lirelle.source.komga.di

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
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.komga.comics.KomgaPageSource
import app.tfdn.lirelle.source.komga.history.KomgaHistory
import app.tfdn.lirelle.source.komga.library.EmptyKomgaAudio
import app.tfdn.lirelle.source.komga.library.EmptyKomgaAudioBookmarks
import app.tfdn.lirelle.source.komga.library.EmptyKomgaMarks
import app.tfdn.lirelle.source.komga.library.EmptyKomgaReadAlong
import app.tfdn.lirelle.source.komga.library.KomgaLibrary
import app.tfdn.lirelle.source.komga.library.KomgaLibraryApi
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.reading.KomgaReader
import app.tfdn.lirelle.source.komga.reading.KomgaReadingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn

/**
 * What Komga can do (spec 2c): no audio, no annotations, no ratings, no reading sessions, authors only as metadata,
 * three reading statuses and its own Home rows; its Library opens on its series (spec 3a2). The same for every Komga
 * server.
 */
private val KomgaCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = true,
    authors = false,
    history = true,
    homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded),
    shelfKinds = setOf(ShelfKind.Collection),
    searchKinds = setOf(SearchKind.Books, SearchKind.Series),
    bookSorts = BookSort.entries.toSet() - BookSort.Author,
    statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
    formatFilters = FormatFilter.entries.toSet() - FormatFilter.Audio,
    libraryStart = LibraryStart.Series,
)

/**
 * What the open profile's Komga server says of its books, its readings and its history — its library and history
 * from 3a2, the places of its comics from 3b — and what it can do, the same for every Komga server. Made with the
 * profile, and gone with it.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object KomgaLibraryModule {

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun library(session: ProfileSession, api: KomgaApis<KomgaLibraryApi>): LibraryRepository =
        KomgaLibrary(session, api, KomgaErrors())

    /** Places written as this device: its name, and the profile's id as its id, the same for the profile's life. */
    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun reader(
        session: ProfileSession,
        api: KomgaApis<KomgaReadingApi>,
        pages: KomgaPageSource,
        deviceLabel: DeviceLabelProvider,
    ): ReaderRepository = KomgaReader(session, api, KomgaErrors(), pages, deviceLabel)

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun audio(): AudioRepository = EmptyKomgaAudio()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun audioBookmarks(): AudioBookmarkRepository = EmptyKomgaAudioBookmarks()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun marks(): MarksRepository = EmptyKomgaMarks()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun readAlong(): ReadAlongRepository = EmptyKomgaReadAlong()

    @Provides
    @ProfileScoped
    @SourceOf(ServerKind.Komga)
    fun history(
        session: ProfileSession,
        api: KomgaApis<KomgaLibraryApi>,
        cache: ReadRecordCache,
    ): HistoryRepository = KomgaHistory(session, api, KomgaErrors(), cache)

    /** Komga's column of the table. */
    @Provides
    @SourceOf(ServerKind.Komga)
    fun capabilities(): SourceCapabilities = KomgaCapabilities
}
