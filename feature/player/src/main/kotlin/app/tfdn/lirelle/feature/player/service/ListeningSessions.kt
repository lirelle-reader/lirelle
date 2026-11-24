package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.session.ListeningSessionClock
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingSessionReport

/**
 * The listening sessions of the book playing, declared as they end or pause, for the file being heard.
 * Call it on the main thread.
 *
 * @param send declares a session: queued for the account, sent after the positions.
 */
class ListeningSessions(
    private val clocks: () -> ListeningSessionClock,
    private val send: (fileId: FileId, report: ReadingSessionReport) -> Unit,
) {
    private var clock = clocks()
    private var fileId: FileId? = null

    /**
     * A tick while playing [fileId] at [bookTimeMs] of a book of [totalMs] (null when its length is not
     * known); a session closed by a long pause is sent, and the one going on at each 5 minutes of listening.
     */
    fun playing(fileId: FileId, bookTimeMs: Long, totalMs: Long?) {
        val previous = this.fileId ?: fileId
        this.fileId = fileId
        clock.onPlaying(bookTimeMs, totalMs)?.let { send(previous, it) }
        report(clock.due())
    }

    /** Playback paused: the session so far is declared, and goes on if playback resumes soon. */
    fun paused() {
        clock.onPaused()
        report(clock.checkpoint())
    }

    /** Playback waits for data: not listening, nothing declared yet. */
    fun stalled() = clock.onPaused()

    /** The book ends, or another replaces it. */
    fun close() {
        report(clock.close())
        clock = clocks()
        fileId = null
    }

    private fun report(report: ReadingSessionReport?) {
        val file = fileId ?: return
        report?.let { send(file, it) }
    }
}
