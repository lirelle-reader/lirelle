package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepTimer

/** Runs an action when playback reaches a place in the book, exactly; one at a time. */
interface ExactPause {
    /** Replaces the planned one, unless it is already at [bookTimeMs]. */
    fun at(bookTimeMs: Long, action: () -> Unit)

    fun cancel()
}

/**
 * Applies the [SleepTimer] to the engine: the fade on the volume, the pause, and the pause at the exact end
 * of the chapter (planned with [ExactPause] between two ticks). Call it on the main thread.
 */
class SleepDriver(
    private val engine: Player,
    private val navigator: BookNavigator,
    private val timer: SleepTimer,
    private val exactPause: ExactPause,
) {
    /** The end of chapter planned with [exactPause]. */
    private var armed: Long? = null

    fun start(choice: SleepChoice, fade: Boolean) {
        val bookTime = navigator.bookTimeMs() ?: return
        val chapter = navigator.playlist?.chapters?.getOrNull(navigator.chapterIndex()) ?: return
        timer.start(choice, bookTime, chapter.endOrNever, fade)
        engine.volume = 1f
        plan(bookTime)
    }

    /** The same duration again (a shake, the sheet), the fade undone. */
    fun extend() {
        timer.extend()
        disarm()
        engine.volume = 1f
    }

    fun cancel() {
        timer.cancel()
        disarm()
        engine.volume = 1f
    }

    /**
     * A tick while playing, at [bookTimeMs] in a chapter that ends at [chapterEndMs]. "End of chapter" in
     * the last chapter does not pause: the book ends, is finished, and the timer with it. Playback that
     * crossed the end waited for on its own pauses, even into the last chapter.
     */
    fun tick(bookTimeMs: Long, chapterEndMs: Long) {
        val endOfBook = endOfChapter && chapterEndMs >= lastChapterEnd && bookTimeMs >= lastChapterEnd
        val tick = timer.onTick(bookTimeMs, chapterEndMs)
        when {
            !tick.pauseNow -> {
                engine.volume = tick.volume
                plan(bookTimeMs)
            }
            endOfBook -> disarm()
            else -> stop()
        }
    }

    /** Playback was moved (a seek, a skip, a chapter jump): "End of chapter" now waits for the end it is in. */
    fun onSeek() = timer.moved()

    /**
     * The engine moved on to its next file at [bookTimeMs]: when that crosses the end of the chapter the
     * timer waits for (the exact pause came too late), playback stops there.
     */
    fun onNextFile(bookTimeMs: Long) {
        val target = armed ?: return
        if (bookTimeMs >= target) reachEnd(target)
    }

    /** "End of chapter" stops exactly at the end: planned on the engine, not left to the next tick. */
    private fun plan(bookTimeMs: Long) {
        val left = if (endOfChapter) timer.msUntilPause(bookTimeMs) else null
        val target = left?.let { bookTimeMs + it }
        if (target == null || target >= lastChapterEnd) {
            disarm()
            return
        }
        armed = target
        exactPause.at(target) { reachEnd(target) }
    }

    /** Playback reached [target]: the pause, if it is still the end the timer waits for (a seek may have moved it). */
    private fun reachEnd(target: Long) {
        if (timer.msUntilPause(target) == 0L && timer.onTick(target, target).pauseNow) stop()
    }

    private val endOfChapter: Boolean get() = timer.state.value?.choice == SleepChoice.EndOfChapter

    private val lastChapterEnd: Long get() = navigator.playlist?.chapters?.lastOrNull()?.endOrNever ?: Long.MAX_VALUE

    private fun disarm() {
        armed = null
        exactPause.cancel()
    }

    private fun stop() {
        disarm()
        engine.pause()
        engine.volume = 1f
    }
}

/** [ExactPause] on ExoPlayer: a message delivered on the main thread when playback reaches the place. */
@OptIn(UnstableApi::class)
class EngineExactPause(
    private val engine: ExoPlayer,
    private val navigator: BookNavigator,
) : ExactPause {
    private var message: PlayerMessage? = null
    private var target: Long? = null

    override fun at(bookTimeMs: Long, action: () -> Unit) {
        if (target == bookTimeMs && message?.isCanceled == false) return
        cancel()
        val place = navigator.playlist?.endPlaceOf(bookTimeMs) ?: return
        target = bookTimeMs
        message = engine.createMessage { _, _ -> action() }
            .setLooper(Looper.getMainLooper())
            .setPosition(place.index, place.positionMs)
            .setDeleteAfterDelivery(true)
            .send()
    }

    override fun cancel() {
        message?.cancel()
        message = null
        target = null
    }
}
