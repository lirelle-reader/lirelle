package app.tfdn.lirelle.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.audio.bookmarks.AudioBookmarks
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkActions
import app.tfdn.lirelle.feature.player.bookmarks.BookmarksPart
import app.tfdn.lirelle.feature.player.controller.PlayerController
import app.tfdn.lirelle.feature.player.controller.PlayerControllerScope
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.controller.StopWatchingAfter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the player screen does once, besides showing its state. */
sealed interface PlayerEvent {
    /** The book was closed from the end panel, or a narration took its place: the screen leaves. */
    data object Leave : PlayerEvent
}

/**
 * The player screen: the [PlayerController]'s state, with "Go back" after a chapter chosen or a newer
 * position followed, the time listened to a finished book and the audiobook's bookmarks, and what the user
 * does ([PlayerActions]).
 * The screen calls [onShown] each time it comes to the front, which looks for a newer position from
 * elsewhere.
 */
@HiltViewModel
class PlayerViewModel private constructor(
    private val controller: PlayerController,
    private val jumps: JumpsPart,
    private val listening: ListeningTime,
    private val marks: BookmarksPart,
    private val capabilities: SourceCapabilities,
) : ViewModel(),
    PlayerActions,
    PlaybackControls by controller,
    ChapterJumps by jumps,
    NewerPositionActions by jumps,
    EndPanelActions by controller,
    PlayerSettingsActions by controller,
    LoadActions by controller,
    BookmarkActions by marks {

    /** [scope]: the controller's, as long as the profile: a bookmark changed as the screen goes is still changed. */
    @Inject
    constructor(
        controller: PlayerController,
        library: LibraryRepository,
        changes: ReadingChanges,
        bookmarks: AudioBookmarks,
        @PlayerControllerScope scope: CoroutineScope,
        capabilities: SourceCapabilities,
    ) : this(
        controller,
        JumpsPart(controller),
        ListeningTime(library, changes.version),
        bookmarks,
        scope,
        capabilities,
    )

    /** A bookmark is gone to as a chapter is: [jumps] offers "Go back". */
    private constructor(
        controller: PlayerController,
        jumps: JumpsPart,
        listening: ListeningTime,
        bookmarks: AudioBookmarks,
        scope: CoroutineScope,
        capabilities: SourceCapabilities,
    ) : this(controller, jumps, listening, BookmarksPart(bookmarks, scope, jumps::jumpTo), capabilities)

    /** False when the source has no audiobooks: the player, reached by an old link, goes back (spec 2c). */
    val offered: Boolean = capabilities.audiobooks

    private val mutableEvents = Channel<PlayerEvent>(Channel.BUFFERED)

    /** One collector: each event is delivered once, and kept until it is collected. */
    val events: Flow<PlayerEvent> = mutableEvents.receiveAsFlow()

    init {
        // Collected in the view model's own scope: the controller, which outlives it, holds nothing of it.
        viewModelScope.launch { controller.closed.collect { mutableEvents.send(PlayerEvent.Leave) } }
    }

    /** Whether the narration shown has had its [PlayerEvent.Leave]: once for each. */
    private var leftForNarration = false

    val state: StateFlow<PlayerUiState> = controller.state
        .onEach(::leaveForNarration)
        .onEach(jumps::onState)
        // A source without reading sessions has no listening time to give, nor one without audio bookmarks any
        // bookmarks: neither is asked (spec 2c).
        .onEach { if (capabilities.readingSessions) listening.onState(it, viewModelScope) }
        .onEach { if (capabilities.audioBookmarks) marks.onState(it) }
        .combine(jumps.shown) { state, goBackShown -> state.copy(goBackShown = goBackShown) }
        .combine(marks.shown) { state, bookmarks -> state.copy(bookmarks = bookmarks) }
        .combine(listening.listened, listening::applyTo)
        .map { it.copy(capabilities = capabilities) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds),
            PlayerUiState(capabilities = capabilities),
        )

    /** The screen came to the front. */
    fun onShown() = controller.checkNewerPosition()

    /** The wait for nothing loaded, once started: one per screen, however many times it is asked. */
    private var leavingWhenEmpty: Job? = null

    /**
     * A player made again from its saved state (after the system ended the app, or a rotation, or back
     * from the player settings) leaves by itself once the session, reached, shows that nothing is loaded.
     * The first snapshot, before the session is reached, is always empty: it does not count. Asked again
     * by the same screen, the wait already started goes on: there is one.
     */
    fun leaveIfNothingLoaded() {
        if (leavingWhenEmpty != null) return
        leavingWhenEmpty = viewModelScope.launch {
            controller.nothingLoaded.first { it }
            mutableEvents.send(PlayerEvent.Leave)
        }
    }

    /**
     * A narration (a read-along book's audio) is heard in the reader: a player screen left open, or made again
     * over one, leaves as it does when nothing is loaded, rather than show it as an audiobook.
     */
    private fun leaveForNarration(state: PlayerUiState) {
        if (state.narrating && !leftForNarration) mutableEvents.trySend(PlayerEvent.Leave)
        leftForNarration = state.narrating
    }
}
