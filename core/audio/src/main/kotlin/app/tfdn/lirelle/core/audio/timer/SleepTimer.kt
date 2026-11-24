package app.tfdn.lirelle.core.audio.timer

import app.tfdn.lirelle.core.library.MonotonicClock
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A timed choice fades over its last minute: the same window as shake-to-extend, time enough to react. */
private const val FadeMs = 60_000L
private const val LastMinuteMs = 60_000L

/** Where the fade ends, in decibels: the volume falls evenly to the ear from 0 dB down to it. */
private const val FadeFloorDb = -40.0
private const val DecibelsPerTenfold = 20.0
private const val MillisPerMinute = 60_000L
private const val ResumeBeforeMs = 5_000L

/** The most one tick adds to the countdown: a longer gap between ticks is a pause, not listening. */
private const val MaxTickMs = 2_000L

sealed interface SleepChoice {
    data class Minutes(val minutes: Int) : SleepChoice {
        init {
            require(minutes > 0) { "A sleep timer lasts at least a minute." }
        }
    }

    data object EndOfChapter : SleepChoice
}

/** @property remainingMs null for [SleepChoice.EndOfChapter]. */
data class SleepState(val choice: SleepChoice, val remainingMs: Long?, val fading: Boolean)

/** @property volume 0 to 1. */
data class SleepTick(val volume: Float, val pauseNow: Boolean)

/**
 * The volume factor with [leftMs] of the fade left: linear in decibels, 0 dB with the whole fade left,
 * [FadeFloorDb] at its end; a factor of 10^(dB/20).
 */
internal fun fadeVolume(leftMs: Long): Float {
    val gone = 1.0 - leftMs.coerceIn(0L, FadeMs).toDouble() / FadeMs
    return 10.0.pow(FadeFloorDb * gone / DecibelsPerTenfold).toFloat()
}

/**
 * Stops playback after a while or at the end of the chapter.
 *
 * Threading: every call, [onTick] and [extend] included, is made on the main thread (the shake
 * detector posts its [extend] there); nothing here is synchronised.
 *
 * Tick contract: [onTick] is called at least once a second while playing, with the book time and the
 * end of the chapter that book time is in (the next chapter's end exactly at a chapter boundary).
 * Use [msUntilPause] to schedule the pause exactly between two ticks.
 *
 * A timed choice counts listening time only: each tick adds the time since the previous one on the
 * monotonic clock, at most 2 seconds, so a pause (no ticks) or a sleeping process does not use it
 * up. It fades the volume over its last minute, the window in which a shake extends it (unless started
 * without the fade, which pauses at the exact end at full volume): evenly in decibels, from 0 dB
 * down to -40 dB (a factor of 0.01) as the end comes, so that the fall is heard all along rather
 * than all at once at the end, as a linear factor sounds. The end of the chapter pauses exactly
 * there, with no fade, and follows seeks and chapter skips ([moved]); playback that crosses the end on its
 * own pauses even when the tick only sees it from the next chapter.
 */
class SleepTimer(private val clock: MonotonicClock) {
    private class Running(val choice: SleepChoice, val fade: Boolean, var leftMs: Long, var chapterEnd: Long) {
        var lastTickAt = 0L
        var awaitingNextChapter = false
        var fadeBeganAt: Long? = null

        /** Adds the listening since the previous tick, at most [MaxTickMs]; returns what is left. */
        fun countDown(now: Long): Long {
            leftMs -= (now - lastTickAt).coerceIn(0L, MaxTickMs)
            lastTickAt = now
            return leftMs
        }

        /** A seek, a skip or a chapter jump happened since the last tick: a new chapter end is a new target. */
        var moved = false

        /**
         * Book milliseconds to the end of the chapter, following seeks; [Long.MAX_VALUE] when unknown. Playback
         * that went past the end waited for with no seek in between (the tick came after the engine crossed
         * into the next chapter, before the exact pause or the next file was heard of) crossed that end:
         * nothing is left, and playback pauses.
         */
        fun untilChapterEnd(bookTimeMs: Long, chapterEndMs: Long): Long {
            val seeked = moved
            moved = false
            if (chapterEndMs != chapterEnd) {
                if (!seeked && !awaitingNextChapter && bookTimeMs >= chapterEnd) return chapterEnd - bookTimeMs
                // A skip, a seek, or the next chapter asked for: the target is the end of the chapter playback is in.
                chapterEnd = chapterEndMs
                awaitingNextChapter = false
            } else if (awaitingNextChapter && bookTimeMs >= chapterEndMs) {
                // The end of the last chapter: there is no next one to wait for.
                awaitingNextChapter = false
            }
            return if (awaitingNextChapter) Long.MAX_VALUE else chapterEnd - bookTimeMs
        }
    }

    private var running: Running? = null
    private var stoppedAt: Long? = null
    private var lastMinute = false

    private val mutableState = MutableStateFlow<SleepState?>(null)
    val state: StateFlow<SleepState?> = mutableState

    /** True during the last minute and the fade: when a shake extends the timer. */
    val inLastMinute: Boolean get() = running != null && lastMinute

    /** @param nowBookTimeMs where playback is; [chapterEndMs] is the end of the chapter it is in. */
    fun start(choice: SleepChoice, nowBookTimeMs: Long, chapterEndMs: Long, fade: Boolean = true) {
        require(nowBookTimeMs >= 0) { "A book time is not negative." }
        stoppedAt = null
        val timer = Running(choice, fade, 0L, chapterEndMs)
        running = timer
        arm(timer)
        publish(timer)
    }

    /** The same duration again, or the end of the next chapter. */
    fun extend() {
        val timer = running ?: return
        arm(timer)
        if (timer.choice == SleepChoice.EndOfChapter) timer.awaitingNextChapter = true
        timer.fadeBeganAt = null
        lastMinute = false
        publish(timer)
    }

    /**
     * Forgets the timer. A fade in progress is not undone here: the caller sets the volume back to
     * 1 (a tick with no timer returns volume 1).
     */
    fun cancel() {
        running = null
        stoppedAt = null
        lastMinute = false
        mutableState.value = null
    }

    /**
     * Playback was moved (a seek, a skip, a chapter jump): the next chapter end seen is where "End of
     * chapter" now stops. Without it, a new chapter end seen past the old one is that end crossed.
     */
    fun moved() {
        running?.moved = true
    }

    /** Where playback resumes after a stop by the timer: 5 seconds before the fade began. */
    fun resumePoint(): Long? = stoppedAt?.let { (it - ResumeBeforeMs).coerceAtLeast(0L) }

    /** [resumePoint], once: it is forgotten, so a later manual play or seek cannot reuse it. */
    fun consumeResumePoint(): Long? = resumePoint().also { stoppedAt = null }

    /**
     * How long until the planned pause: listening milliseconds for a timed choice, book milliseconds
     * for the end of the chapter (divide by the speed for the wall time). Null without a timer, and
     * while the end of the next chapter, asked for by [extend], is not known yet.
     */
    fun msUntilPause(bookTimeMs: Long): Long? {
        val timer = running ?: return null
        return when (timer.choice) {
            is SleepChoice.Minutes -> timer.leftMs
            SleepChoice.EndOfChapter -> if (timer.awaitingNextChapter) null else timer.chapterEnd - bookTimeMs
        }?.coerceAtLeast(0L)
    }

    /** The volume factor for this tick and whether to pause now; records where the fade began. */
    fun onTick(bookTimeMs: Long, chapterEndMs: Long): SleepTick {
        val timer = running ?: return SleepTick(1f, false)
        val left = when (timer.choice) {
            is SleepChoice.Minutes -> timer.countDown(clock.nowMillis())
            SleepChoice.EndOfChapter -> timer.untilChapterEnd(bookTimeMs, chapterEndMs)
        }
        lastMinute = left <= LastMinuteMs
        val fading = timer.fade && timer.choice is SleepChoice.Minutes && left <= FadeMs
        if ((fading || left <= 0) && timer.fadeBeganAt == null) timer.fadeBeganAt = bookTimeMs
        if (left <= 0) {
            stoppedAt = timer.fadeBeganAt
            running = null
            lastMinute = false
            mutableState.value = null
            return SleepTick(if (fading) 0f else 1f, true)
        }
        publish(timer)
        return SleepTick(if (fading) fadeVolume(left) else 1f, false)
    }

    private fun arm(timer: Running) {
        val choice = timer.choice
        if (choice is SleepChoice.Minutes) {
            timer.leftMs = choice.minutes * MillisPerMinute
            timer.lastTickAt = clock.nowMillis()
        }
    }

    private fun publish(timer: Running) {
        mutableState.value = when (timer.choice) {
            is SleepChoice.Minutes -> {
                SleepState(timer.choice, timer.leftMs, timer.fade && timer.leftMs <= FadeMs)
            }
            SleepChoice.EndOfChapter -> SleepState(timer.choice, null, false)
        }
    }
}
