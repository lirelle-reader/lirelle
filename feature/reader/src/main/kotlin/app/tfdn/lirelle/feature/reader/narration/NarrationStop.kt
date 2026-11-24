package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.reader.ReaderCore

/**
 * "Stop listening" from the reader itself: the narration's bar swiped away while the narration does not play, to
 * read on without it; "Undo" loads it again where it was left, paused.
 */
internal class NarrationStop(private val core: ReaderCore, private val player: ReaderNarrationPlayer) :
    ReaderNarrationStop {

    /** The file of the narration stopped last, until "Undo" or another stop. */
    private var stopped: FileId? = null

    override fun stopNarration() {
        val active = core.current.narration.active ?: return
        val fileId = core.fileId ?: return
        // A narration that plays is not swiped away: the bar is under the pages turned by a swipe.
        if (active.playing) return
        stopped = fileId
        player.actions.stop()
    }

    override fun undoStopNarration() {
        val fileId = stopped ?: return
        stopped = null
        player.narrate(core.bookId, fileId, NarrationStart.Resume, play = false)
    }
}
