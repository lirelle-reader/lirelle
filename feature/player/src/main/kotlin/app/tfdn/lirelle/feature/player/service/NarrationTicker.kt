package app.tfdn.lirelle.feature.player.service

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sections skipped together smaller than this are a title page or the like: not worth telling the reader. */
const val SkippedNoticeBytes: Long = 6 * 1024L

/**
 * Tells the [NarrationCursor] where the narration loaded in the service is, for the reader to follow: the
 * sentence heard, when it changed (another sentence, or playing ↔ paused); and the sections without narration
 * that playing went past, when they are more than a title page ([SkippedNoticeBytes]). A seek goes past
 * nothing: the reader was taken there. Call it on the main thread.
 *
 * It looks every [period] while playing only; paused, once when playback stops or its state changes, and at
 * each move (a seek).
 *
 * An audiobook tells nothing; leaving a narration for one, or for none, says so (no sentence).
 */
@OptIn(UnstableApi::class)
class NarrationTicker(
    private val engine: Player,
    private val navigator: BookNavigator,
    private val cursor: NarrationCursor,
    private val scope: CoroutineScope,
    private val period: Duration = 100.milliseconds,
) : Player.Listener {
    private data class Followed(
        val bookId: BookId,
        val fileId: FileId,
        val map: ReadAlongMap,
        val playlist: BookPlaylist,
    )

    private var followed: Followed? = null
    private var ticker: Job? = null
    private var told: HeardSentence? = null

    /** The section of the last tick; null after a seek or a load, so that the next one goes past nothing. */
    private var section: Int? = null

    fun start() = engine.addListener(this)

    fun stop() {
        engine.removeListener(this)
        ticker?.cancel()
    }

    /** [book] was loaded in the service (null: none is any more). Another load ends a narration's end. */
    fun follow(book: LoadedBook?) {
        ticker?.cancel()
        section = null
        if (book != null && cursor.finished.value != null) cursor.finish(null)
        val narration = book?.source as? BookSource.Narration
        if (book == null || narration == null) {
            followed = null
            if (told != null) cursor.publish(null)
            told = null
            return
        }
        followed = Followed(book.bookId, narration.fileId, narration.map, book.playlist)
        onIsPlayingChanged(engine.isPlaying)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        ticker?.cancel()
        if (followed == null) return
        if (!isPlaying) return tick()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(period)
            }
        }
    }

    /** Paused or played while held (waiting for data): the sentence's playing state changed. */
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = tick()

    /** At the end, among others: it is no longer playing. */
    override fun onPlaybackStateChanged(playbackState: Int) = tick()

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            section = null
        }
        tick()
    }

    private fun tick() {
        // Only while the engine holds the narration followed: not while another book is being opened.
        val narration = followed?.takeIf { it.playlist === navigator.playlist } ?: return
        val time = navigator.bookTimeMs() ?: return
        // Before the first sentence, the first one; past the end, the last one.
        val sentence = narration.map.sentenceAt(time) ?: narration.map.nextSentence(time) ?: return
        val playing = engine.playWhenReady && engine.playbackState != Player.STATE_ENDED
        val heard = HeardSentence(
            narration.bookId, narration.fileId, sentence.sectionIndex, sentence.fragment, sentence.textHref, playing,
        )
        if (heard != told) {
            told = heard
            cursor.publish(heard)
        }
        val previous = section
        section = sentence.sectionIndex
        if (previous != null && sentence.sectionIndex > previous) {
            val skipped = narration.map.skippedBetween(previous, sentence.sectionIndex)
            if (skipped.sumOf { it.sizeBytes ?: 0 } >= SkippedNoticeBytes) {
                // The map has no title for a section without narration: the reader words it.
                cursor.announce(SkippedNotice(narration.bookId, skipped.first().index, title = null))
            }
        }
    }
}
