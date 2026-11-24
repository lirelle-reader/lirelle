package app.tfdn.lirelle.feature.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ChangeFollower
import app.tfdn.lirelle.core.library.Freshness
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.isNoNetwork
import app.tfdn.lirelle.core.ui.toLoadable
import app.tfdn.lirelle.feature.book.download.KeptBookCheck
import app.tfdn.lirelle.feature.book.download.KeptPage
import app.tfdn.lirelle.feature.book.download.KeptPages
import app.tfdn.lirelle.feature.book.download.offlineListening
import app.tfdn.lirelle.feature.book.download.offlineReading
import app.tfdn.lirelle.feature.book.download.ownReadingAsRecentAs
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @property reading null until it arrives, and when its call failed: the block is not shown.
 * @property related empty lists until they arrive, and for those whose call failed.
 * @property refreshing true while a reload the user asked for is on its way, the page staying on screen.
 * @property refreshError why the last reload the user asked for failed; the page stays on screen.
 * @property listening where the listening of a book with audio is: loading until it arrives (never asked
 *   for a book without audio), failed when its call did and nothing was known before, null inside for a
 *   book never listened to.
 * @property offline true when the page is built from what the device keeps of a downloaded book, the server
 *   not having given it: [reading] is then the device's position, and nothing else of the server is there. Each
 *   time the page comes back to the front, [reading] and [listening] are read again from the device, unless the
 *   server answers again and the page becomes its own.
 * @property noNetwork true when that is for lack of a network, and not because the book is gone from the server.
 * @property keptFormats on an [offline] page, the formats the device has whole: the only ones that open.
 */
data class BookUiState(
    val book: Loadable<BookDetail> = Loadable.Loading,
    val reading: BookReading? = null,
    val related: RelatedBooks = RelatedBooks(),
    val refreshing: Boolean = false,
    val refreshError: ApiError? = null,
    val listening: Loadable<PlaybackState?> = Loadable.Loading,
    val offline: Boolean = false,
    val noNetwork: Boolean = false,
    val keptFormats: Set<DownloadFormat> = emptySet(),
)

/**
 * The rows without the books a row above already shows: for an author who only wrote the series,
 * "By the same author" would otherwise be "In the series" again. A row left empty is not shown.
 */
internal fun RelatedBooks.withoutRepeats(): RelatedBooks {
    val inSeriesIds = inSeries.mapTo(mutableSetOf()) { it.id }
    val ownSameAuthor = sameAuthor.filterNot { it.id in inSeriesIds }
    val aboveIds = inSeriesIds + ownSameAuthor.map { it.id }
    return copy(sameAuthor = ownSameAuthor, similar = similar.filterNot { it.id in aboveIds })
}

/**
 * The page of a downloaded book that [error] kept from the server: the reading is the device's position,
 * and the listening the one the device knows; when it knows none it is said not to be known, so that the
 * player goes on from where it knows.
 */
private fun KeptPage.offline(error: ApiError) = BookUiState(
    book = Loadable.Ready(detail),
    reading = offlineReading(),
    listening = offlineListening(error),
    offline = true,
    noNetwork = error.isNoNetwork,
    keptFormats = complete,
)

/** A failure for which a downloaded book shows the page the device keeps. */
private val ApiError.showsKeptPage: Boolean get() = isNoNetwork || this == ApiError.NotFound

@HiltViewModel(assistedFactory = BookViewModel.Factory::class)
class BookViewModel @AssistedInject constructor(
    @Assisted bookIdText: String,
    private val repository: LibraryRepository,
    private val audio: AudioRepository,
    clock: MonotonicClock,
    private val changes: ReadingChanges,
    private val kept: KeptPages,
    private val check: KeptBookCheck,
    /** What the source offers: the page leaves out what it cannot give (spec 2c). */
    val capabilities: SourceCapabilities,
) : ViewModel() {

    /** The factory takes the id's text: an assisted parameter cannot be a value class. */
    @AssistedFactory
    interface Factory {
        fun create(bookIdText: String): BookViewModel
    }

    private val bookId = BookId(bookIdText)

    private val mutableState = MutableStateFlow(BookUiState())
    val state: StateFlow<BookUiState> = mutableState.asStateFlow()

    private val freshness = Freshness(clock)

    /** The load, refresh or quiet reload under way: one at a time, so an old answer never replaces a newer one. */
    private var job: Job? = null

    /**
     * Each time this device changed the reading on the server, however new the page is (the change does not
     * say which book it is about: the one shown is reloaded), a quiet reload: at once while the page is in
     * front, when it is shown again otherwise. A load or a refresh under way is left alone; the reload comes
     * after it.
     */
    private val follower = ChangeFollower(changes, viewModelScope, ::idle, ::reloadAfterChange)

    /** The calls for the reading, the listening and the related books of the book shown. */
    private var details: Job? = null

    /**
     * The page shown has the files of the copy the device keeps, the server not serving the book's own for now: the
     * places read of that copy wait to be sent, and the reading shown is the device's when it is as recent.
     */
    private var onKeptCopy = false

    /** The server's reading of the book shown, once it arrived; null when it could not be had. */
    private var serverReading: BookReading? = null

    /**
     * The page left the front since it was last shown: an offline page coming back looks again. Not at the first
     * showing, which the first load already answers.
     */
    private var away = false

    init {
        // Read before anything is asked: what changed before that is in the first load.
        val known = changes.version.value
        load()
        follower.start(known)
    }

    /**
     * Loads from nothing: the page shows its loading state. Without a network, or for a book gone from the
     * server, a downloaded book shows the page the device keeps.
     */
    fun load() {
        job?.cancel()
        details?.cancel()
        mutableState.value = BookUiState()
        onKeptCopy = false
        serverReading = null
        job = viewModelScope.launch {
            val book = book()
            val error = (book as? ApiResult.Failure)?.error
            val page = error?.takeIf { it.showsKeptPage }?.let { kept.page(bookId) }
            mutableState.value =
                if (error != null && page != null) page.offline(error) else BookUiState(book.toLoadable())
            if (book is ApiResult.Success) loaded(book.value, anew = true)
        }
    }

    /**
     * The server's page of the book. When the server cannot serve the book's files for now (Komga analyses its
     * new file), the files are those of the copy the device keeps, if it keeps the text whole: Read opens them as
     * it does without a network, and nothing is said. A copy still on its way, or being deleted, opens nothing.
     */
    private suspend fun book(): ApiResult<BookDetail> {
        val answer = repository.book(bookId)
        val served = (answer as? ApiResult.Success)?.value ?: return answer
        onKeptCopy = false
        val book = served.takeIf { it.filesUnavailable } ?: return answer
        val copy = kept.page(book.id)?.takeIf { DownloadFormat.Text in it.complete }?.detail ?: return answer
        val withCopy = book.copy(
            files = copy.files,
            readerFileWithoutDownload = copy.readerFileWithoutDownload,
            filesUnavailable = false,
        )
        onKeptCopy = true
        return ApiResult.Success(withCopy)
    }

    /** Pull to refresh: the page stays, a failure is said. It takes the place of a quiet reload under way. */
    fun refresh() {
        if (mutableState.value.book !is Loadable.Ready) {
            load()
            return
        }
        job?.cancel()
        mutableState.update { it.copy(refreshing = true, refreshError = null) }
        job = viewModelScope.launch { reload(silent = false) }
    }

    /**
     * The page is in front again: reload quietly if the reading changed meanwhile, or if what it shows is
     * old and nothing is being loaded. A page with the files of the kept copy shows what was read of it meanwhile,
     * which the server does not have: the device's places wait to be sent, and say no change.
     *
     * An [offline][BookUiState.offline] page coming back (from the reader or the player, say) reloads quietly too:
     * the network back, it becomes the server's page by itself; still without the server, it shows where the
     * device is now in the book and its listening, read again from what the device keeps.
     */
    fun onShown() {
        val back = away
        away = false
        if (follower.shown()) return
        if (job?.isActive == true || mutableState.value.book !is Loadable.Ready) return
        job = when {
            mutableState.value.offline -> if (back) viewModelScope.launch { reload(silent = true) } else return
            freshness.isStale() -> viewModelScope.launch { reload(silent = true) }
            onKeptCopy -> viewModelScope.launch {
                val own = kept.page(bookId)?.ownReadingAsRecentAs(serverReading)
                if (own != null) mutableState.update { it.copy(reading = own) }
            }
            else -> return
        }
    }

    /** The page is no longer in front: a change of the reading waits until it is again. */
    fun onHidden() {
        away = true
        follower.hidden()
    }

    /** A quiet reload after a change; a page that shows no book stays as it is. */
    private fun reloadAfterChange() {
        if (mutableState.value.book is Loadable.Ready) job = viewModelScope.launch { reload(silent = true) }
    }

    /** Waits until nothing is being loaded, whatever took the place of what was. */
    private suspend fun idle() {
        while (true) (job?.takeIf { it.isActive } ?: return).join()
    }

    private suspend fun reload(silent: Boolean) {
        when (val book = book()) {
            // What is shown is new again, whoever asked: an error said before no longer holds.
            is ApiResult.Success -> {
                val anew = !silent || mutableState.value.offline
                mutableState.update {
                    it.copy(
                        book = Loadable.Ready(book.value),
                        refreshing = false,
                        refreshError = null,
                        offline = false,
                        noNetwork = false,
                        keptFormats = emptySet(),
                    )
                }
                loaded(book.value, anew)
            }
            is ApiResult.Failure -> {
                // The page the device keeps already says there is no network.
                if (!silent) {
                    mutableState.update {
                        it.copy(refreshing = false, refreshError = book.error.takeUnless { _ -> it.offline })
                    }
                }
                if (mutableState.value.offline && book.error.showsKeptPage) keptAgain(book.error)
            }
        }
    }

    /**
     * An offline page still without the server: where the device is now in the book and its listening, read again
     * from what it keeps, as the page was built.
     */
    private suspend fun keptAgain(error: ApiError) {
        val page = kept.page(bookId) ?: return
        mutableState.update { it.copy(reading = page.offlineReading(), listening = page.offlineListening(error)) }
    }

    /**
     * The reading, the listening and the related books of before stay until those of [book] arrive. They are
     * asked apart from the book: a reload that is cancelled or fails leaves the calls for the book on screen running.
     *
     * A kept book is also checked for a newer version when the page is shown [anew]: at the first load, one the
     * user asked for, or one that ends a page built from the device; not at the quiet reloads that follow each
     * change of the reading. The check goes on whatever becomes of the page.
     */
    private fun loaded(book: BookDetail, anew: Boolean) {
        freshness.markLoaded()
        if (anew) viewModelScope.launch { check.check(bookId) }
        details?.cancel()
        // The book that answered may not be the one asked for: a merged book is replaced.
        details = viewModelScope.launch {
            launch {
                val server = (repository.bookReading(book.id) as? ApiResult.Success)?.value
                serverReading = server
                // On a page with the files of the kept copy: the server's reading lacks the places read of it.
                val own = if (onKeptCopy) kept.page(book.id)?.ownReadingAsRecentAs(server) else null
                val shown = own ?: server
                if (shown != null) mutableState.update { it.copy(reading = shown) }
                // What a downloaded book shows without a network is what was last seen with one: the server's.
                kept.keep(book, server)
            }
            if (book.hasAudioFiles) {
                launch {
                    val listening = audio.playbackState(book.id)
                    // A failure does not hide what was known.
                    mutableState.update {
                        if (listening is ApiResult.Success || it.listening !is Loadable.Ready) {
                            it.copy(listening = listening.toLoadable())
                        } else {
                            it
                        }
                    }
                }
            }
            launch {
                val related = repository.relatedBooks(book.id)
                mutableState.update { it.copy(related = related.withoutRepeats()) }
            }
        }
    }
}
