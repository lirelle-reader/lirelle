package app.tfdn.lirelle.core.audio.timer

import app.tfdn.lirelle.core.library.MonotonicClock
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SleepTimerTest {

    private var now = 1_000_000L
    private val timer = SleepTimer(MonotonicClock { now })

    private val chapterEnd = 3_600_000L

    private var book = 100_000L

    private fun tick(bookTime: Long = book, end: Long = chapterEnd) = timer.onTick(bookTime, end)

    /** Playing for [ms]: a tick each second, the last second left for the caller's own tick. */
    private fun listen(ms: Long) {
        var left = ms
        while (left > TICK_MS) {
            now += TICK_MS
            book += TICK_MS
            timer.onTick(book, chapterEnd)
            left -= TICK_MS
        }
        now += left
        book += left
    }

    /** Not playing: no tick comes, time goes by. */
    private fun pauseFor(ms: Long) {
        now += ms
    }

    @Test
    fun `no timer means full volume and no pause`() {
        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value).isNull()
        assertThat(timer.resumePoint()).isNull()
    }

    @Test
    fun `a timed choice keeps full volume until the last minute`() {
        timer.start(SleepChoice.Minutes(5), 0, chapterEnd)
        listen(5 * 60_000L - 60_001L)

        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value).isEqualTo(SleepState(SleepChoice.Minutes(5), 60_001L, false))
    }

    @Test
    fun `the volume falls evenly in decibels over the last minute, then playback pauses`() {
        timer.start(SleepChoice.Minutes(2), 0, chapterEnd)
        listen(60_000L)
        val start = tick()
        assertThat(start.volume).isWithin(EPS).of(1f)
        assertThat(start.pauseNow).isFalse()
        assertThat(timer.state.value!!.fading).isTrue()
        assertThat(timer.inLastMinute).isTrue()

        listen(30_000L)
        // Half way: -20 dB.
        assertThat(tick().volume).isWithin(EPS).of(0.1f)

        listen(29_000L)
        // A second before the end: close to -40 dB.
        assertThat(tick().volume).isWithin(EPS).of(0.0108f)

        listen(1_000L)
        assertThat(tick()).isEqualTo(SleepTick(0f, true))
        assertThat(timer.state.value).isNull()
    }

    @Test
    fun `the fade is linear in decibels, from 0 down to -40`() {
        assertThat(fadeVolume(60_000L)).isWithin(EPS).of(1f)
        assertThat(fadeVolume(45_000L)).isWithin(EPS).of(0.3162f)
        assertThat(fadeVolume(30_000L)).isWithin(EPS).of(0.1f)
        assertThat(fadeVolume(15_000L)).isWithin(EPS).of(0.0316f)
        assertThat(fadeVolume(0L)).isWithin(EPS).of(0.01f)
        assertThat(fadeVolume(90_000L)).isWithin(EPS).of(1f)
    }

    @Test
    fun `without the fade it pauses at the deadline at full volume`() {
        timer.start(SleepChoice.Minutes(1), 0, chapterEnd, fade = false)
        listen(55_000L)
        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value!!.fading).isFalse()

        listen(5_000L)
        assertThat(tick().pauseNow).isTrue()
    }

    @Test
    fun `end of chapter pauses exactly at the chapter end with no fade`() {
        timer.start(SleepChoice.EndOfChapter, 100_000L, chapterEnd)

        assertThat(tick(chapterEnd - 1, chapterEnd)).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value).isEqualTo(SleepState(SleepChoice.EndOfChapter, null, false))
        assertThat(tick(chapterEnd, chapterEnd)).isEqualTo(SleepTick(1f, true))
        assertThat(timer.state.value).isNull()
    }

    @Test
    fun `extending a timed choice during the last minute starts the same duration again`() {
        timer.start(SleepChoice.Minutes(5), 0, chapterEnd)
        listen(5 * 60_000L - 30_000L)
        tick()
        assertThat(timer.inLastMinute).isTrue()

        timer.extend()

        assertThat(timer.inLastMinute).isFalse()
        assertThat(timer.state.value).isEqualTo(SleepState(SleepChoice.Minutes(5), 5 * 60_000L, false))
        assertThat(tick()).isEqualTo(SleepTick(1f, false))
    }

    @Test
    fun `extending during the fade brings the volume back`() {
        timer.start(SleepChoice.Minutes(2), 0, chapterEnd)
        listen(116_000L)
        assertThat(tick().volume).isLessThan(1f)
        assertThat(timer.inLastMinute).isTrue()

        timer.extend()

        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value!!.fading).isFalse()
        assertThat(timer.resumePoint()).isNull()
    }

    @Test
    fun `extending the end of chapter waits for the end of the next chapter`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        tick(chapterEnd - 30_000L, chapterEnd)
        assertThat(timer.inLastMinute).isTrue()

        timer.extend()

        assertThat(timer.inLastMinute).isFalse()
        assertThat(tick(chapterEnd - 10_000L, chapterEnd).pauseNow).isFalse()
        val next = chapterEnd + 1_800_000L
        assertThat(tick(chapterEnd, next).pauseNow).isFalse()
        assertThat(tick(chapterEnd + 1, next).pauseNow).isFalse()
        assertThat(tick(next - 1, next).pauseNow).isFalse()
        assertThat(tick(next, next).pauseNow).isTrue()
    }

    @Test
    fun `the countdown advances only while playing`() {
        timer.start(SleepChoice.Minutes(10), 0, chapterEnd)
        listen(3 * 60_000L)
        tick()
        pauseFor(60 * 60_000L)

        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        val remaining = timer.state.value!!.remainingMs!!
        assertThat(remaining).isAtLeast(7 * 60_000L - 3_000L)
        assertThat(remaining).isAtMost(7 * 60_000L)
    }

    @Test
    fun `the fade still comes in the last minute of listening after a pause`() {
        timer.start(SleepChoice.Minutes(1), 0, chapterEnd)
        listen(30_000L)
        tick()
        pauseFor(10 * 60_000L)
        tick()
        listen(20_000L)

        assertThat(tick().volume).isLessThan(1f)
        listen(8_000L)
        assertThat(tick().pauseNow).isTrue()
    }

    @Test
    fun `msUntilPause is null without a timer`() {
        assertThat(timer.msUntilPause(100_000L)).isNull()
    }

    @Test
    fun `the end of the chapter is known before it is crossed, at one second ticks`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        timer.onTick(chapterEnd - 1_600L, chapterEnd)
        val decision = tick(chapterEnd - 600L, chapterEnd)

        assertThat(decision.pauseNow).isFalse()
        assertThat(timer.msUntilPause(chapterEnd - 600L)).isEqualTo(600L)
        assertThat(timer.msUntilPause(chapterEnd)).isEqualTo(0L)
    }

    @Test
    fun `a timed choice says how much listening is left`() {
        timer.start(SleepChoice.Minutes(2), 0, chapterEnd)
        listen(30_000L)
        tick()

        assertThat(timer.msUntilPause(book)).isEqualTo(90_000L)
    }

    @Test
    fun `after the next chapter was asked for, the pause is unknown until its end is`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        timer.extend()
        tick(chapterEnd - 5_000L, chapterEnd)
        assertThat(timer.msUntilPause(chapterEnd - 5_000L)).isNull()

        val next = chapterEnd + 1_800_000L
        tick(chapterEnd + 500L, next)
        assertThat(timer.msUntilPause(chapterEnd + 500L)).isEqualTo(1_799_500L)
    }

    @Test
    fun `skipping to the next chapter moves the end of chapter target without pausing`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        val next = chapterEnd + 1_800_000L
        timer.moved()

        assertThat(tick(chapterEnd + 10_000L, next)).isEqualTo(SleepTick(1f, false))
        assertThat(timer.msUntilPause(chapterEnd + 10_000L)).isEqualTo(1_790_000L)
        assertThat(tick(next, next).pauseNow).isTrue()
    }

    @Test
    fun `seeking back to an earlier chapter waits for its end`() {
        val earlier = 1_000_000L
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        timer.moved()

        assertThat(tick(500_000L, earlier)).isEqualTo(SleepTick(1f, false))
        assertThat(tick(earlier, earlier).pauseNow).isTrue()
    }

    @Test
    fun `seeking while the next chapter is awaited follows the new chapter`() {
        val earlier = 1_000_000L
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        timer.extend()

        assertThat(tick(500_000L, earlier).pauseNow).isFalse()
        assertThat(tick(earlier, earlier).pauseNow).isTrue()
    }

    @Test
    fun `playback that crosses the end of the chapter on its own pauses, though the tick sees the next chapter`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        assertThat(tick(chapterEnd - 400L, chapterEnd).pauseNow).isFalse()
        val next = chapterEnd + 300_000L

        // The tick came once the engine was in the next file, before the exact pause was heard of.
        assertThat(tick(chapterEnd + 100L, next).pauseNow).isTrue()

        assertThat(timer.state.value).isNull()
        assertThat(timer.consumeResumePoint()).isEqualTo(chapterEnd + 100L - 5_000L)
    }

    @Test
    fun `a seek into the next chapter is followed, not taken for the end crossed`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        tick(chapterEnd - 400L, chapterEnd)
        val next = chapterEnd + 300_000L

        timer.moved()

        assertThat(tick(chapterEnd + 10_000L, next).pauseNow).isFalse()
        assertThat(timer.msUntilPause(chapterEnd + 10_000L)).isEqualTo(290_000L)
        // The seek is used up: the next end crossed on its own pauses.
        assertThat(tick(next + 100L, next + 300_000L).pauseNow).isTrue()
    }

    @Test
    fun `a seek within the chapter is used up by the next tick`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        timer.moved()
        tick(chapterEnd - 60_000L, chapterEnd)

        assertThat(tick(chapterEnd + 100L, chapterEnd + 300_000L).pauseNow).isTrue()
    }

    @Test
    fun `extending the end of the last chapter pauses at the end of the book`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        timer.extend()

        assertThat(tick(chapterEnd - 1_000L, chapterEnd).pauseNow).isFalse()
        assertThat(tick(chapterEnd, chapterEnd).pauseNow).isTrue()
    }

    @Test
    fun `a gap of 5 seconds between ticks counts 2 seconds`() {
        timer.start(SleepChoice.Minutes(10), 0, chapterEnd)
        pauseFor(5_000L)
        tick()

        assertThat(timer.state.value!!.remainingMs).isEqualTo(10 * 60_000L - 2_000L)
    }

    @Test
    fun `the resume point is given once`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        tick(chapterEnd, chapterEnd)

        assertThat(timer.consumeResumePoint()).isEqualTo(chapterEnd - 5_000L)
        assertThat(timer.consumeResumePoint()).isNull()
        assertThat(timer.resumePoint()).isNull()
    }

    @Test
    fun `a duration must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { SleepChoice.Minutes(0) }
        assertThrows(IllegalArgumentException::class.java) { SleepChoice.Minutes(-5) }
    }

    @Test
    fun `cancel restores the volume and forgets the timer`() {
        timer.start(SleepChoice.Minutes(1), 0, chapterEnd)
        listen(57_000L)
        assertThat(tick().volume).isLessThan(1f)

        timer.cancel()

        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value).isNull()
        assertThat(timer.resumePoint()).isNull()
    }

    @Test
    fun `the resume point is 5 seconds before the fade began, 65 seconds before the stop`() {
        timer.start(SleepChoice.Minutes(2), 0, chapterEnd)
        listen(60_000L)
        tick()
        val fadeBegan = book
        listen(60_000L)
        assertThat(tick().pauseNow).isTrue()

        assertThat(timer.resumePoint()).isEqualTo(fadeBegan - 5_000L)
        assertThat(timer.consumeResumePoint()).isEqualTo(book - 65_000L)
        assertThat(timer.resumePoint()).isNull()
    }

    @Test
    fun `a shake during the fade brings the full volume back at once, and no resume point`() {
        timer.start(SleepChoice.Minutes(2), 0, chapterEnd)
        listen(100_000L)
        assertThat(tick().volume).isLessThan(0.3f)

        timer.extend()

        assertThat(tick()).isEqualTo(SleepTick(1f, false))
        assertThat(timer.state.value).isEqualTo(SleepState(SleepChoice.Minutes(2), 2 * 60_000L, false))
        listen(60_000L)
        assertThat(tick()).isEqualTo(SleepTick(1f, false))
    }

    @Test
    fun `without the fade the resume point is 5 seconds before the pause`() {
        timer.start(SleepChoice.Minutes(1), 0, chapterEnd, fade = false)
        listen(60_000L)
        tick(bookTime = 300_000L)

        assertThat(timer.resumePoint()).isEqualTo(295_000L)
    }

    @Test
    fun `at the end of a chapter the resume point is 5 seconds before its end`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        tick(chapterEnd, chapterEnd)

        assertThat(timer.resumePoint()).isEqualTo(chapterEnd - 5_000L)
    }

    @Test
    fun `the resume point never goes before the start of the book`() {
        timer.start(SleepChoice.EndOfChapter, 0, 3_000L)
        tick(3_000L, 3_000L)

        assertThat(timer.resumePoint()).isEqualTo(0L)
    }

    @Test
    fun `starting again replaces the timer and its resume point`() {
        timer.start(SleepChoice.EndOfChapter, 0, chapterEnd)
        tick(chapterEnd, chapterEnd)

        timer.start(SleepChoice.Minutes(10), 0, chapterEnd)

        assertThat(timer.resumePoint()).isNull()
        assertThat(timer.state.value!!.remainingMs).isEqualTo(10 * 60_000L)
    }

    private companion object {
        const val EPS = 0.001f
        const val TICK_MS = 1_000L
    }
}
