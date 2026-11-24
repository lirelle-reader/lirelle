package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.controller.NarrationCommands
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.player.service.NarrationStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The app's one player for the reader, through its narration [controller] (the `PlayerController`). The
 * narration and its failure are watched only while the playback service runs ([running]): watching them
 * connects to the service, which would start it for every book opened; a service that does not run holds no
 * narration. The sentence heard comes from the process's [cursor], which needs no connection. The bar's and
 * the panel's [actions] are the controller's too.
 */
internal class ControllerNarrationPlayer(
    private val controller: NarrationCommands,
    private val cursor: NarrationCursor,
    running: StateFlow<Boolean>,
    override val actions: NarrationActions,
) : ReaderNarrationPlayer {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val narration: Flow<NarrationUi?> =
        running.flatMapLatest { if (it) controller.narration else flowOf(null) }

    override val heard: StateFlow<HeardSentence?> get() = cursor.sentence

    override val skipped: Flow<SkippedNotice> get() = controller.skipped

    // Watched as the narration is: the controller's failure watches the session too. A failure only exists once a
    // narration was asked, which started the service.
    @OptIn(ExperimentalCoroutinesApi::class)
    override val failure: Flow<NarrationFailure?> =
        running.flatMapLatest { if (it) controller.narrationFailure else flowOf(null) }

    override fun narrate(bookId: BookId, fileId: FileId, start: NarrationStart, play: Boolean) =
        controller.narrate(bookId, fileId, start, play)

    override fun pause() = controller.pauseNarration()

    override fun play() = controller.playNarration()

    override fun retryNarration() = controller.retryNarration()
}
