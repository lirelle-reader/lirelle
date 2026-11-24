package app.tfdn.lirelle.feature.player.service

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val StoreEvery = 5.seconds

/** Where playback is: in the book, and in the item playing. */
private data class PlayingPlace(val bookTimeMs: Long, val itemIndex: Int, val itemPositionMs: Long)

/**
 * What happens while a book plays, on the main thread: every [period] while playing the position goes to
 * the book's [PositionSink] (stored, sent every 5 s), the sleep timer ticks, the listening session counts. On
 * a pause, a seek or a chapter change the position is sent at once; at the end of the book it is sent, the
 * session closed, and [Events.onFinished] called.
 */
@OptIn(UnstableApi::class)
class PlaybackSessionLoop(
    private val engine: Player,
    private val navigator: BookNavigator,
    private val sleep: SleepDriver,
    private val sessions: ListeningSessions,
    private val scope: CoroutineScope,
    private val events: Events,
    private val period: Duration = 500.milliseconds,
) : Player.Listener {

    interface Events {
        /** Every tick: the chapter may have changed. */
        fun onTick()

        /** The user (or the system) paused: not a stall for data. */
        fun onPaused()

        /** The last file ended. */
        fun onFinished()
    }

    /**
     * Where the positions of the book go, set as each book opens: an audiobook's playback states, a
     * narration's places. Only the positions of its own playlist go there; none before it is set.
     */
    var positions: BookPositions? = null

    private var ticker: Job? = null
    private var playing = false
    private var lastChapter = -1

    /**
     * Paused by the user (or the system), not stalled: where playback resumes was chosen, so the sleep timer's
     * end of chapter follows it, as after a seek. A stall (the next file loading at a chapter end) does not.
     */
    private var resumesAfterPause = false

    /** Ticks since the position was last stored: the device's store is written every 5 s, not every tick. */
    private var ticksSinceStored = Int.MAX_VALUE
    private val storeEvery = (StoreEvery / period).toInt().coerceAtLeast(1)

    fun start() = engine.addListener(this)

    fun stop() {
        engine.removeListener(this)
        ticker?.cancel()
    }

    /** The position now, stored and sent at once. */
    fun flush() {
        val (sink, place) = placeNow() ?: return
        ticksSinceStored = 0
        scope.launch { sink.flushNow(place.bookTimeMs, place.itemIndex, place.itemPositionMs) }
    }

    /** The book is being left (another one, closed, the service ends): its position, then its session. */
    fun leave() {
        flush()
        sessions.close()
        lastChapter = -1
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        ticker?.cancel()
        if (isPlaying) {
            if (resumesAfterPause) sleep.onSeek()
            resumesAfterPause = false
            ticker = scope.launch {
                while (isActive) {
                    tick()
                    delay(period)
                }
            }
            return
        }
        if (engine.playbackState == Player.STATE_ENDED) return
        val held = engine.playbackState == Player.STATE_BUFFERING ||
            engine.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        if (engine.playWhenReady && held) {
            // A stall for data or an interruption: not listening, but not a pause either.
            sessions.stalled()
            return
        }
        flush()
        sessions.paused()
        // A failure stops playback too, but play then is not a resume after a pause.
        if (!engine.playWhenReady) {
            resumesAfterPause = true
            events.onPaused()
        }
    }

    /** Paused while already held (a stall, a long interruption, the network): a pause all the same. */
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady || playing || navigator.playlist == null) return
        if (engine.playbackState == Player.STATE_ENDED) return
        resumesAfterPause = true
        flush()
        sessions.paused()
        events.onPaused()
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            // Moved on purpose: the sleep timer's end of chapter follows; crossed by playing, it pauses.
            sleep.onSeek()
            flush()
            lastChapter = navigator.chapterIndex()
        }
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) navigator.bookTimeMs()?.let(sleep::onNextFile)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (navigator.playlist == null) return
        if (playbackState == Player.STATE_IDLE && engine.playerError != null && !playing) {
            // A failure while already held (a stall): what was heard is kept as for a failure while playing.
            flush()
            sessions.paused()
            return
        }
        if (playbackState != Player.STATE_ENDED) return
        flush()
        sessions.close()
        sleep.cancel()
        events.onFinished()
    }

    private fun tick() {
        val book = navigator.playlist ?: return
        val bookTime = navigator.bookTimeMs() ?: return
        val chapterIndex = book.chapterIndexAt(bookTime)
        if (ticksSinceStored >= storeEvery) {
            placeNow()?.let { (sink, place) ->
                // Stamped here, on the tick: a busy main thread must not stretch the five seconds.
                val tickedAt = sink.now()
                scope.launch { sink.onTick(place.bookTimeMs, place.itemIndex, place.itemPositionMs, tickedAt) }
            }
            ticksSinceStored = 0
        }
        ticksSinceStored++
        if (lastChapter >= 0 && chapterIndex != lastChapter) flush()
        lastChapter = chapterIndex
        book.chapters.getOrNull(chapterIndex)?.let { sleep.tick(bookTime, it.endOrNever) }
        book.assets.getOrNull(engine.currentMediaItemIndex)?.let {
            // The book's percentage only when its length is known: a part of it would mean nothing.
            sessions.playing(it.fileId, bookTime, book.timeline.totalMs.takeIf { book.timeline.durationsKnown })
        }
        events.onTick()
    }

    /** The book's sink and where playback is; null without a book, or before the book's sink is set. */
    private fun placeNow(): Pair<PositionSink, PlayingPlace>? {
        val book = navigator.playlist ?: return null
        val sink = positions?.takeIf { it.playlist === book }?.sink ?: return null
        val bookTime = navigator.bookTimeMs() ?: return null
        return sink to PlayingPlace(bookTime, engine.currentMediaItemIndex, engine.currentPosition.coerceAtLeast(0))
    }
}
