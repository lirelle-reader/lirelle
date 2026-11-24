package app.tfdn.lirelle.feature.player.controller

import android.util.Log
import app.tfdn.lirelle.core.audio.position.ListeningConflicts
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.position.PlaybackSyncEvents
import app.tfdn.lirelle.core.audio.position.ReadingBaselines
import app.tfdn.lirelle.core.audio.position.followServer
import app.tfdn.lirelle.core.audio.position.keepOurs
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.downloads.DeviceCopyControl
import app.tfdn.lirelle.core.downloads.DeviceCopySources
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.feature.player.EndPanelActions
import app.tfdn.lirelle.feature.player.LoadActions
import app.tfdn.lirelle.feature.player.PlaybackControls
import app.tfdn.lirelle.feature.player.PlayerSettingsActions
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import javax.inject.Inject
import javax.inject.Qualifier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val Tag = "PlayerController"

/** How long a check for a newer position waits for the session to show its book. */
private val SessionWait = 5.seconds

/** Two places this close are the same place. */
private const val SamePlaceMs = 5_000L

/** A scope on the main thread that lives as long as the profile, for the player's controller. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlayerControllerScope

/** Where the controller's offers and ratings come from, among the objects of its profile. */
class PlayerSources @Inject constructor(
    val audio: AudioRepository,
    val reader: ReaderRepository,
    val store: PlaybackStore,
    val outbox: PlaybackOutbox,
    val changes: ReadingChanges,
    val settings: PlayerSettingsStore,
    val syncEvents: PlaybackSyncEvents,
    val conflicts: ListeningConflicts,
    val baselines: ReadingBaselines,
)

/**
 * The app's side of the one player: the playback service's session seen through a [PlayerConnection]
 * (a MediaController), as the [PlayerUiState] the player screen and the mini-player show, and
 * commanded by its controls ([PlaybackControls]), the end panel's ([EndPanelActions]), the settings the
 * player screen changes ([PlayerSettingsActions]) and "Try again" after a failed load ([LoadActions]).
 *
 * It offers a newer position from elsewhere (spec 8.3):
 * - when the player comes back to the front ([checkNewerPosition], which waits for the session);
 * - for every book whose writes the flusher drops against a state written elsewhere
 *   ([ListeningConflicts.standing]), from the moment the controller exists, so that a conflict met while
 *   no screen was open is offered when one opens. This path does not use the session: where the device
 *   is comes from its stored position. A state at the same place is followed without a word.
 *
 * A narration (the audio of a read-along book, [NarrationCommands]) shows its book in the session too, but it is
 * not that book's audiobook: no end panel, rating or newer position is looked for while one is loaded, and the
 * state says it is one ([PlayerUiState.narrating]).
 *
 * Positions themselves are written by the service. Call it on the main thread.
 *
 * One per profile: what it remembers — the session shown, the last book asked for, the end panel, the offers —
 * is of that profile's books, and goes with it when the account leaves.
 */
@ProfileScoped
class PlayerController private constructor(
    private val connection: PlayerConnection,
    private val sources: PlayerSources,
    private val scope: CoroutineScope,
    private val offer: NewerPositionOffer,
    private val ending: EndOfListening,
    private val requests: LoadRequests,
    private val running: StateFlow<Boolean>,
    private val narrations: NarrationControls,
) : PlaybackControls by SessionControls(connection),
    EndPanelActions by ending,
    PlayerSettingsActions by SettingsChanges(sources.settings, scope),
    LoadActions by requests,
    NarrationCommands by narrations {

    @Inject
    constructor(
        connection: PlayerConnection,
        sources: PlayerSources,
        narration: NarrationSources,
        device: DeviceCopySources,
        @PlayerControllerScope scope: CoroutineScope,
        capabilities: SourceCapabilities,
    ) : this(connection, sources, narration, device, scope, PlaybackServiceRunning.running, capabilities)

    /**
     * [running]: whether the playback service runs. [device]: what the end panel's row works with. [capabilities]:
     * what the profile's source can do (spec 2c).
     */
    internal constructor(
        connection: PlayerConnection,
        sources: PlayerSources,
        narration: NarrationSources,
        device: DeviceCopySources,
        scope: CoroutineScope,
        running: StateFlow<Boolean>,
        capabilities: SourceCapabilities,
    ) : this(
        connection, sources, scope,
        LoadRequests(connection::send) { connection.snapshot.value.extras.finished?.nextVolume?.bookId },
        running,
        narration,
        DeviceCopyControl(device, scope),
        capabilities,
    )

    /** The end panel's commands go through [requests]: "Listen to the next one" is a request to try again. */
    private constructor(
        connection: PlayerConnection,
        sources: PlayerSources,
        scope: CoroutineScope,
        requests: LoadRequests,
        running: StateFlow<Boolean>,
        narration: NarrationSources,
        device: DeviceCopyControl,
        capabilities: SourceCapabilities,
    ) : this(
        connection, sources, scope,
        NewerPositionOffer(sources.audio, sources.store, sources.outbox, sources.conflicts),
        EndOfListening(requests::forward, scope, device, capabilities.ratings) { id ->
            ListeningRating(id, sources.reader, sources.baselines, sources.outbox, sources.changes, scope)
        },
        requests,
        running,
        // A narration asked for is not a book to load again: "Try again" is for the books this app asked for.
        NarrationControls(connection, narration, sources.settings.settings, scope, onNarrate = requests::forget),
    )

    /** The last audiobook the session showed. */
    private var shownBook: BookId? = null

    /** By book, the version of the standing state already looked at (null inside: it could not be read). */
    private val looked = mutableMapOf<BookId, String?>()

    /** The rows of the chapters sheet, kept while the chapters and the current one stay. */
    private val rows = ChapterRows()

    /** Watching it keeps the connection to the session open. */
    val state: StateFlow<PlayerUiState> = combine(
        connection.snapshot.onEach(::follow),
        offer.offers,
        // The end panel, with what the device keeps of its book.
        ending.state.combine(ending.device.copy) { end, copy -> end.copy(device = copy) },
        sources.settings.settings,
        requests.retryable,
    ) { session, offers, end, settings, retryable ->
        playerUiState(session, session.bookId?.let(offers::get), end, settings, rows).copy(canRetryLoad = retryable)
    }.stateIn(scope, SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds), PlayerUiState())

    /** The book was closed from the end panel. */
    val closed: SharedFlow<Unit> = ending.closed

    /**
     * True once the session is reached and holds no book, nor is reading one or failed to: nothing is
     * loaded. Watching it keeps the connection open, as [state] does.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nothingLoaded: Flow<Boolean> = running.flatMapLatest { runs ->
        // A service that does not run holds no book: no need to start it to learn so.
        if (!runs) {
            flowOf(true)
        } else {
            combine(connection.connected, connection.snapshot) { connected, session ->
                connected && session.bookId == null && !session.extras.loading && !session.extras.loadFailed
            }
        }
    }

    init {
        scope.launch { sources.conflicts.standing.collect { guarded { onStanding(it) } } }
        scope.launch { sources.syncEvents.manifestChanges.collect { guarded { offer.onManifestChanged(it) } } }
    }

    /**
     * Loads [bookId] and plays it, from its position, or from its start with [fromStart] ("Listen again");
     * without [play], it is left paused there (a stop undone, for a book that was paused). Always asked
     * of the service, which alone knows whether it already holds the book ready: the session seen here
     * may be an old one, kept after the connection was released.
     */
    fun load(bookId: BookId, fromStart: Boolean = false, play: Boolean = true) {
        // Kept for "Try again"; a narration's failure is no longer the one to show.
        requests.asked(bookId, fromStart, play)
        narrations.forget()
        connection.send(PlayerCommand.LoadBook(bookId, fromStart = fromStart, play = play))
    }

    /** The start of the chapter [index]. */
    fun goToChapter(index: Int) = connection.seekTo(index, 0)

    /** A place on the whole book's timeline. */
    fun seekBookTime(bookTimeMs: Long) = connection.send(PlayerCommand.SeekBookTime(bookTimeMs))

    /**
     * Back at the front: the server's state of the audiobook the session shows, offered if it is worth it. A
     * narration's place is not the audiobook's: the check waits for an audiobook (the one asked for, after a
     * narration of the same book), and there is none to make while a narration goes on.
     */
    fun checkNewerPosition() {
        scope.launch {
            // Not the book of before while one asked for is still on its way: that one is checked.
            val now = withTimeoutOrNull(SessionWait) {
                connection.snapshot.first { session ->
                    val asked = requests.pending()
                    val id = session.audiobookId
                    id != null && !session.extras.loading && (asked == null || asked == id)
                }
            } ?: return@launch
            val id = now.audiobookId ?: return@launch
            // A state that stands against the device's writes is offered by the other way.
            if (id in sources.conflicts.standing.value) return@launch
            offer.check(id) { connection.snapshot.value.bookTimeMs }
        }
    }

    /**
     * "Go there": the server's version becomes the one the next writes are made over, then playback moves
     * to the offered place. False when nothing was offered for the book the session shows.
     */
    fun acceptNewerPosition(): Boolean {
        val id = connection.snapshot.value.audiobookId ?: return false
        val taken = offer.accept(id) ?: return false
        scope.launch {
            sources.conflicts.followServer(id, taken.server.version)
            connection.send(PlayerCommand.SeekBookTime(taken.bookTimeMs))
        }
        return true
    }

    /** "Ignore": the player stays, and its position is written at once over the server's version, so it wins. */
    fun ignoreNewerPosition() {
        val id = connection.snapshot.value.audiobookId ?: return
        val taken = offer.ignore(id) ?: return
        scope.launch { sources.conflicts.keepOurs(id, taken.server.version) }
    }

    /**
     * What the session shows: the end panel's book; an offer of the place playback is at already, followed
     * without a word (never offered); and another audiobook loaded leaves the last one's offer behind. A
     * narration is none of these: the end panel and the offers stay as they were, and the last audiobook stays
     * the one shown, so that the next audiobook decides: the same book keeps its offer, another drops it.
     *
     * The end panel's row is for the copy the device keeps of the audiobook shown: none during a narration, whose
     * end is the reader's.
     */
    private fun follow(session: SessionSnapshot) {
        requests.onSession(session.extras.loading, session.extras.loadFailed)
        ending.device.show(session.audiobookId)
        if (session.narrating) return
        ending.onSession(session.bookId, session.extras.finished != null)
        val id = session.bookId ?: return
        val here = offer.offers.value[id]?.takeIf { !session.extras.loading && it.isAt(session.bookTimeMs) }
        if (here != null && offer.accept(id) != null) {
            scope.launch { sources.conflicts.followServer(id, here.server.version) }
        }
        val before = shownBook
        shownBook = id
        if (before == null || before == id) return
        offer.forget(before)
        scope.launch { sources.conflicts.forget(before) }
    }

    /**
     * The states standing against the device's writes: each new one is offered when it is elsewhere, and
     * followed when it is the same place. One that no longer stands (the device's position won, by
     * "Ignore" or by listening on, or was taken) takes its offer away.
     */
    private suspend fun onStanding(standing: Map<BookId, PlaybackState?>) {
        (looked.keys - standing.keys).forEach { id ->
            looked -= id
            offer.ignore(id)
        }
        for ((id, server) in standing) {
            if (!looked.containsKey(id) || looked[id] != server?.version) look(id, server)
        }
    }

    /** A new state standing for [id]: offered when elsewhere than the device's stored position, followed otherwise. */
    private suspend fun look(id: BookId, server: PlaybackState?) {
        looked[id] = server?.version
        val samePlace = offer.standing(id, server, sources.store.get(id)?.bookTimeMs) ?: return
        sources.conflicts.followServer(id, samePlace.version)
    }
}

/** Whether this offer is the place [bookTimeMs] is at: within 5 s, the newer-position rule's same place. */
private fun Offer.isAt(bookTimeMs: Long): Boolean = abs(this.bookTimeMs - bookTimeMs) <= SamePlaceMs

/** The audiobook the session shows; null for none, and for a narration. */
private val SessionSnapshot.audiobookId: BookId? get() = bookId?.takeUnless { narrating }

/** [block], whose failure is logged: the collector it runs in goes on. */
private suspend fun guarded(block: suspend () -> Unit) {
    val failure = runCatching { block() }.exceptionOrNull() ?: return
    if (failure is CancellationException) throw failure
    Log.w(Tag, "A newer position could not be looked at", failure)
}
