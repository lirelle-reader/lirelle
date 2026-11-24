package app.tfdn.lirelle.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ChangeFollower
import app.tfdn.lirelle.core.library.Freshness
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What Home shows.
 *
 * @property rows the rows the source offers, in display order, each with its own state.
 * @property refreshing true while a pull to refresh is running.
 * @property refreshError why the last pull to refresh did not replace every row.
 * @property now the moment "2 hours ago" is counted back from: when Home was last loaded, refreshed or shown.
 * @property listening by book, the listening of the books of the "Continue" row that have audio, filled
 *   after the row is shown. What was known stays while the row is loaded again.
 * @property expired the profile Home is shown for, while its session waits for a sign-in: Home then says so,
 *   in place of any failure — the server is not asked, so every failure is that one. Null while it does not.
 */
data class HomeUiState(
    val rows: Map<HomeRowKind, Loadable<HomeRow>> = HomeRowKind.entries.loading(),
    val refreshing: Boolean = false,
    val refreshError: ApiError? = null,
    val now: Instant = Instant.EPOCH,
    val listening: Map<BookId, BookListening> = emptyMap(),
    val expired: ExpiredSession? = null,
) {
    val isLoading: Boolean get() = rows.values.any { it is Loadable.Loading }
    val failure: ApiError? get() = rows.values.filterIsInstance<Loadable.Failed>().firstOrNull()?.error
    val allFailed: Boolean get() = rows.values.all { it is Loadable.Failed }
    val isEmpty: Boolean get() = rows.values.all { it is Loadable.Ready && it.value.books.isEmpty() }

    /** Whether a row is drawn: one with books, or the placeholders of one being asked for. */
    val hasRows: Boolean
        get() = rows.values.any { it is Loadable.Loading || (it is Loadable.Ready && it.value.books.isNotEmpty()) }
}

/** Each of these rows, loading. */
private fun List<HomeRowKind>.loading(): Map<HomeRowKind, Loadable<HomeRow>> = associateWith { Loadable.Loading }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    audio: AudioRepository,
    clock: MonotonicClock,
    private val wallClock: Clock,
    private val changes: ReadingChanges,
    signIn: ProfileSignIn,
    capabilities: SourceCapabilities,
) : ViewModel() {
    /** The rows the source offers, in display order: only those are asked for and shown (spec 2c). */
    private val kinds = HomeRowKind.entries.filter { it in capabilities.homeRows }

    private val mutableState = MutableStateFlow(
        HomeUiState(rows = kinds.loading(), now = wallClock.instant(), expired = signIn.expired()),
    )
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()
    private val freshness = Freshness(clock)

    /** The load, refresh or quiet reload under way: one at a time, so an old answer never replaces a newer one. */
    private var job: Job? = null

    private val lookup = ListeningLookup(audio)

    /**
     * Each time this device changed the reading on the server, however new the rows are (a book just read
     * must not show the progress of before), a quiet reload: at once while Home is in front, when it is
     * shown again otherwise. A load or a refresh under way is left alone; the reload comes after it.
     */
    private val follower = ChangeFollower(changes, viewModelScope, ::idle, ::reloadQuietly)

    /**
     * Whether the profile waits for a sign-in, said in the state for the notice. Once its account has signed
     * in again Home loads by itself, which nothing else would ask for: from nothing when a row was never
     * loaded — the failure was the session's —, quietly over the rows it kept otherwise. The failure of a
     * refresh made meanwhile is forgotten first: it was the session's too.
     */
    private val signInFollower = SignInFollower(
        signIn = signIn,
        scope = viewModelScope,
        onChange = { expired -> mutableState.update { it.copy(expired = expired) } },
        onSignedIn = {
            // What a pull failed with while the profile waited was hidden by the notice, and was the session's:
            // it must not show now that the notice is gone, until the reload below has answered.
            mutableState.update { it.copy(refreshError = null) }
            if (state.value.rows.values.all { it is Loadable.Ready }) {
                idle()
                reloadQuietly()
            } else {
                load()
            }
        },
    )

    /** The listening of the books of the "Continue" row shown, being filled. */
    private var listening: Job? = null

    init {
        // Read before anything is asked: what changed before that is in the first load.
        val known = changes.version.value
        load()
        follower.start(known)
        signInFollower.start()
    }

    /** Loads from nothing: every row shows its loading state. What is known of the profile's sign-in stays. */
    fun load() {
        job?.cancel()
        val shown = state.value
        mutableState.value = HomeUiState(
            rows = kinds.loading(),
            now = wallClock.instant(),
            listening = shown.listening,
            expired = shown.expired,
        )
        job = viewModelScope.launch { fetch { kind, result -> put(kind, result.toLoadable()) } }
    }

    /**
     * Pull to refresh: the rows stay; those that answer are replaced, a failure is said. While rows are
     * still loading there is nothing to keep: it is a load, so a row that fails says so and does not wait for ever.
     */
    fun refresh() {
        if (state.value.isLoading) {
            load()
            return
        }
        job?.cancel()
        mutableState.update { it.copy(refreshing = true, refreshError = null, now = wallClock.instant()) }
        job = viewModelScope.launch {
            val error = fetch(::replaceIfLoaded)
            mutableState.update { it.copy(refreshing = false, refreshError = error) }
        }
    }

    /**
     * Home is in front again: the relative times are counted from now, and what it shows is reloaded quietly
     * if the reading changed meanwhile, or if it is old and nothing is being loaded.
     */
    fun onShown() {
        mutableState.update { it.copy(now = wallClock.instant()) }
        if (follower.shown() || job?.isActive == true || !freshness.isStale()) return
        reloadQuietly()
    }

    /** Home is no longer in front: a change of the reading waits until it is again. */
    fun onHidden() = follower.hidden()

    /** Waits until nothing is being loaded, whatever took the place of what was. */
    private suspend fun idle() {
        while (true) (job?.takeIf { it.isActive } ?: return).join()
    }

    private fun reloadQuietly() {
        job = viewModelScope.launch {
            // Every row is new again: the failure a refresh said before no longer holds.
            if (fetch(::replaceIfLoaded) == null) mutableState.update { it.copy(refreshError = null) }
        }
    }

    private fun replaceIfLoaded(kind: HomeRowKind, result: ApiResult<HomeRow>) {
        if (result is ApiResult.Success) put(kind, Loadable.Ready(result.value))
    }

    // Adding to the map keeps the order of its keys, which is the display order.
    private fun put(kind: HomeRowKind, row: Loadable<HomeRow>) {
        mutableState.update { it.copy(rows = it.rows + (kind to row)) }
        if (kind == HomeRowKind.Continue && row is Loadable.Ready) fillListening(row.value)
    }

    /**
     * The listening of each book that has audio, shown as it comes: from the playback state the row brought,
     * asked only when it brought none. A new row takes the place of the old.
     */
    private fun fillListening(row: HomeRow) {
        listening?.cancel()
        listening = viewModelScope.launch {
            row.books.filter { it.hasAudioFiles }.forEach { book ->
                launch {
                    val known = lookup.of(book.id, row.playback) ?: return@launch
                    mutableState.update { it.copy(listening = it.listening + (book.id to known)) }
                }
            }
        }
    }

    /** Asks the rows at once, hands each answer over as it comes, and returns the first failure. */
    private suspend fun fetch(onResult: (HomeRowKind, ApiResult<HomeRow>) -> Unit): ApiError? = coroutineScope {
        val results = kinds
            .map { kind -> async { repository.homeRow(kind).also { onResult(kind, it) } } }
            .awaitAll()
        if (results.any { it is ApiResult.Success }) freshness.markLoaded()
        results.filterIsInstance<ApiResult.Failure>().firstOrNull()?.error
    }
}
