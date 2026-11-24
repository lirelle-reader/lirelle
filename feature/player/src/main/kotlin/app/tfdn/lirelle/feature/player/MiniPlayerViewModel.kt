package app.tfdn.lirelle.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerController
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.controller.StopWatchingAfter
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The mini-player of the shell: the app's one player, as the [PlayerController] shows it, while the
 * playback service runs ([running]). Only then is the player watched, which keeps the connection to
 * its session open, so that a book loaded from anywhere (a book page, Home, the notification) shows
 * here. A service that does not run holds no book: nothing is shown, and nothing connects to it. A narration
 * (a read-along book's audio) shows as such ([narration]), and is stopped and taken up again as a book is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MiniPlayerViewModel internal constructor(
    private val controller: PlayerController,
    running: StateFlow<Boolean>,
    capabilities: SourceCapabilities = SourceCapabilities.Everything,
) : ViewModel() {

    @Inject
    constructor(controller: PlayerController, capabilities: SourceCapabilities) :
        this(controller, PlaybackServiceRunning.running, capabilities)

    /** The player's state, with what the profile's source can do: without ratings the bar offers none. */
    val state: StateFlow<PlayerUiState> = running
        .flatMapLatest { if (it) controller.state else flowOf(PlayerUiState()) }
        .map { it.copy(capabilities = capabilities) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds),
            (if (running.value) controller.state.value else PlayerUiState()).copy(capabilities = capabilities),
        )

    /**
     * The narration loaded, for the bar's line; null for an audiobook, and while the service does not run. What
     * the bar does comes from [state] alone: the two may arrive out of step.
     */
    val narration: StateFlow<NarrationUi?> = running
        .flatMapLatest { if (it) controller.narration else flowOf(null) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds),
            if (running.value) controller.narration.value else null,
        )

    fun playPause() = controller.playPause()

    /** Back by the configured interval. */
    fun back() = controller.back()

    /** The end panel of the finished book again, for "Rate". */
    fun openEndPanel() = controller.openEndPanel()

    /** The book stopped, kept for "Undo": which one, whether it was playing, and the narration it was. */
    private var stopped: Stopped? = null

    /** "Stop listening": the book is closed; [undoStop] loads it again as it was. */
    fun stopListening() {
        val now = state.value
        stopped = Stopped(now.bookId ?: return, now.playing, now.narrationFileId)
        controller.stopListening()
    }

    /**
     * "Undo": the book stopped, loaded again at its saved position (a narration where it was left), playing if
     * it was, paused otherwise.
     */
    fun undoStop() {
        val book = stopped ?: return
        stopped = null
        val fileId = book.narrationFileId
        if (fileId == null) {
            controller.load(book.bookId, play = book.playing)
        } else {
            controller.narrate(book.bookId, fileId, NarrationStart.Resume, play = book.playing)
        }
    }

    private data class Stopped(val bookId: BookId, val playing: Boolean, val narrationFileId: FileId?)
}
